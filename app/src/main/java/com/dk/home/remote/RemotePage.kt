package com.dk.home.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Trang Điều khiển (lấy từ app DK TV). Là một View nằm trong MainActivity, không phải Activity riêng.
 * Kết nối chỉ chạy khi trang đang mở: [setActive] (true khi vuốt tới, false khi rời trang).
 *
 * Các lệnh {"type":"remote", "action": ...} gửi qua DataChannel tới máy TV/máy tính đã ghép nối.
 */
class RemotePage(private val host: Activity) : FrameLayout(host) {

    // ---- trạng thái ----
    private val ui = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private val controller = RemoteController(host.applicationContext)
    private var active = false
    private var connected = false
    private var tvScreenOn = true
    private var tvTouchOff = false
    private var lastWarnAt = 0L
    private var quality = 0

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnScreen: View   // nút CHUYỂN ĐỔI GIAO DIỆN
    private lateinit var btnPower: View    // nút TẮT / MỞ MÀN HÌNH
    private lateinit var powerBox: LinearLayout
    private lateinit var btnTouch: View
    private lateinit var touchBox: LinearLayout
    private lateinit var screenBox: LinearLayout
    private lateinit var btnQ360: TextView
    private lateinit var btnQ480: TextView
    private lateinit var btnQ720: TextView

    private val topBoxes = LinkedHashMap<String, View>()
    private val topNames = linkedMapOf(
        "power" to "Tắt / Mở màn hình",
        "screen" to "Chuyển đổi giao diện",
        "touch" to "Tắt cảm ứng TV",
        "full" to "Phóng to màn hình",
        "mic" to "Giọng nói"
    )

    // ---- chuột cảm ứng ----
    private var lastX = 0f
    private var lastY = 0f
    private var startX = 0f
    private var startY = 0f
    private var downAt = 0L
    private var moved = false
    private var multi = false
    private var pendDx = 0f
    private var pendDy = 0f
    private var pendScroll = 0f
    private var flushScheduled = false
    private var lastMoveT = 0L
    private var scrollActive = false
    private var flingV = 0f
    private var flingLastT = 0L
    private val sampT = LongArray(6)
    private val sampY = FloatArray(6)
    private var sampN = 0
    private var lastFlushAt = 0L
    private var barHoldFired = false

    private val barHold = Runnable {
        barHoldFired = true
        Haptics.tick(this)
        send(JSONObject().put("action", "key").put("key", "bar"))
    }

    private val modeListener: (String) -> Unit = { ui.post { refreshScreenButton() } }

    init {
        controller.statusListener = object : RemoteController.StatusListener {
            override fun onConnectionStatusChanged(connectedCount: Int) {
                setConnected(connectedCount > 0)
            }
        }
        addView(buildUi())
        setConnected(false)
    }

    /** Mở hoặc đóng trang. Mở: bắt đầu kết nối. Đóng: ngắt kết nối. */
    fun setActive(on: Boolean) {
        if (on == active) return
        active = on
        if (on) {
            host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            RemoteState.addListener(modeListener)
            controller.start(RemoteConfig.savedCode(host))
        } else {
            controller.stop()
            RemoteState.removeListener(modeListener)
            host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setConnected(false)
        }
    }

    /** Dọn tài nguyên khi MainActivity bị huỷ. */
    fun destroy() {
        setActive(false)
        sender.shutdown()
    }

    /** MainActivity chuyển kết quả nhận dạng giọng nói về đây. */
    fun onVoiceResult(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) return
        val q = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!q.isNullOrBlank()) send(JSONObject().put("action", "voice").put("q", q.trim()))
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun neon(fill: Int, edge: Int, cutDp: Float = 10f, glow: Boolean = true, grid: Boolean = false): Drawable =
        NeonDrawable(fill, edge, cutDp, glow = glow, grid = grid)

    /** Nút vát góc 2 trạng thái: thường / đang nhấn (sáng viền). */
    private fun neonBtn(fill: Int, edge: Int, pressedFill: Int, cutDp: Float = 10f): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), NeonDrawable(pressedFill, edge, cutDp, glow = true))
        addState(intArrayOf(), NeonDrawable(fill, edge, cutDp, glow = false))
    }

    private fun caption(t: String) = TextView(host).apply {
        text = t; textSize = 10f; setTextColor(Neon.DIM); letterSpacing = 0.14f
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD); gravity = Gravity.CENTER
    }

    private fun labeled(btn: View, label: String, size: Int = 56, compact: Boolean = false) = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        addView(btn, LinearLayout.LayoutParams(dp(size), dp(size)))
        addView(caption(label).also { if (compact) { it.textSize = 9f; it.letterSpacing = 0.05f } },
            LinearLayout.LayoutParams(-2, -2).also { it.topMargin = dp(if (compact) 3 else 6) })
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi(): View {
        val root = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0F1426.toInt(), Neon.BG))
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }

        // đầu trang: tên neon + trạng thái kết nối
        val head = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        title.addView(TextView(host).apply {
            text = "DK TV"; textSize = 18f; setTextColor(Neon.CYAN); letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        })
        title.addView(TextView(host).apply {
            text = "REMOTE"; textSize = 10f; setTextColor(Neon.MAGENTA); letterSpacing = 0.3f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }, LinearLayout.LayoutParams(-2, -2).also { it.leftMargin = dp(8); it.bottomMargin = dp(3) })
        head.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        statusText = TextView(host).apply {
            textSize = 10.5f; letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        statusDot = View(host)
        val chip = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = neon(Neon.PANEL, Neon.EDGE, 8f, glow = false)
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        chip.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).also { it.rightMargin = dp(8) })
        chip.addView(statusText)
        // chạm vào thẻ trạng thái để đổi mã ghép nối
        chip.isClickable = true
        chip.setOnClickListener { showCodeDialog() }
        // nút ⚙: bật/tắt các nút trên màn hình điều khiển
        val gear = TextView(host).apply {
            text = "\u2699\uFE0E"; textSize = 18f; gravity = Gravity.CENTER; setTextColor(Neon.CYAN)
            background = neon(Neon.PANEL, Neon.EDGE, 8f, glow = false)
            contentDescription = "Bật/tắt nút"
            setOnClickListener { showButtonsDialog() }
        }
        head.addView(gear, LinearLayout.LayoutParams(dp(36), dp(34)).also { it.rightMargin = dp(8) })
        head.addView(chip)
        root.addView(head, LinearLayout.LayoutParams(-1, -2))
        // đường kẻ gradient neon cyan -> tím -> hồng
        root.addView(View(host).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Neon.CYAN, Neon.VIOLET, Neon.MAGENTA))
        }, LinearLayout.LayoutParams(-1, dp(2)).also { it.topMargin = dp(8) })

        // hàng trên: tắt/mở màn hình, chuyển đổi giao diện, khoá cảm ứng TV, phóng to, giọng nói
        btnPower = IconButton(host, IconButton.POWER).also {
            it.rect = true
            it.setOnClickListener {
                tvScreenOn = !tvScreenOn
                send(JSONObject().put("action", "screen").put("on", tvScreenOn))
                refreshPowerButton()
            }
        }
        powerBox = labeled(btnPower, "TẮT\nMÀN HÌNH", 56, true)
        // chuyển đổi giao diện: đang ở TV/YouTube -> về màn hình chính của app; đang ở màn hình chính -> về màn hình chính của TV
        val btnHome = IconButton(host, IconButton.HOME).also {
            it.rect = true
            it.setOnClickListener { send(JSONObject().put("action", "home")) }
        }
        btnScreen = btnHome
        val btnMic = IconButton(host, IconButton.MIC).also {
            it.rect = true
            it.setOnClickListener { startVoice() }
        }
        val top = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        btnTouch = IconButton(host, IconButton.TOUCH).also {
            it.rect = true
            it.setOnClickListener {
                tvTouchOff = !tvTouchOff
                send(JSONObject().put("action", "touchlock").put("on", tvTouchOff))
                refreshTouchButton()
            }
        }
        val btnFull = IconButton(host, IconButton.FULL).also {
            it.rect = true
            it.setOnClickListener { send(JSONObject().put("action", "fullscreen")) }
        }
        screenBox = labeled(btnScreen, "CHUYỂN ĐỔI\nGIAO DIỆN", 56, true)
        touchBox = labeled(btnTouch, "TẮT CẢM\nỨNG TV", 56, true)
        val fullBox = labeled(btnFull, "PHÓNG TO\nMÀN HÌNH", 56, true)
        val micBox = labeled(btnMic, "GIỌNG\nNÓI", 56, true)
        topBoxes.clear()
        topBoxes["power"] = powerBox; topBoxes["screen"] = screenBox; topBoxes["touch"] = touchBox
        topBoxes["full"] = fullBox; topBoxes["mic"] = micBox
        for (b in topBoxes.values) top.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        applyButtonVisibility()
        root.addView(top, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        refreshPowerButton()
        refreshScreenButton()
        refreshTouchButton()

        // khung chuột cảm ứng: tối vát góc, viền neon cyan, lưới kẻ ô
        val pad = FrameLayout(host).apply { background = neon(Neon.PANEL, Neon.CYAN, 24f, glow = true, grid = true) }
        // 5 phím: lên / xuống / trái / phải / OK, nằm trong khung chuột
        fun key(kind: Int, k: String, w: Int, h: Int, gravity: Int, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) {
            val v = DPadButton(host, kind).also { btn ->
                btn.setOnClickListener {
                    if (k == "down" && barHoldFired) { barHoldFired = false; return@setOnClickListener }   // vừa giữ 2s: không tính là bấm
                    send(JSONObject().put("action", "key").put("key", k))
                }
                // giữ ▼ đúng 2 giây: nhảy xuống thanh dưới cùng của TV
                if (k == "down") btn.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            barHoldFired = false
                            ui.removeCallbacks(barHold); ui.postDelayed(barHold, BAR_HOLD_MS)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> ui.removeCallbacks(barHold)
                    }
                    false
                }
            }
            pad.addView(v, FrameLayout.LayoutParams(dp(w), dp(h), gravity).also {
                it.setMargins(dp(l), dp(t), dp(r), dp(b))
            })
        }
        key(DPadButton.UP, "up", 100, 52, Gravity.TOP or Gravity.CENTER_HORIZONTAL, t = 24)
        key(DPadButton.DOWN, "down", 100, 52, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, b = 24)
        key(DPadButton.LEFT, "left", 52, 100, Gravity.START or Gravity.CENTER_VERTICAL, l = 26)
        key(DPadButton.RIGHT, "right", 52, 100, Gravity.END or Gravity.CENTER_VERTICAL, r = 26)
        key(DPadButton.OK, "ok", 72, 72, Gravity.CENTER)
        attachTouchpad(pad)
        root.addView(pad, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = dp(10); it.bottomMargin = dp(4) })
        root.addView(View(host), LinearLayout.LayoutParams(-1, dp(8)))

        val panel = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
        // hàng 1: 2 thanh trượt (âm lượng | độ sáng)
        val sliders = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        sliders.addView(sliderRow("ÂM LƯỢNG", "volume", 50, Neon.CYAN, Neon.VIOLET), LinearLayout.LayoutParams(0, -2, 1f).also { it.rightMargin = dp(5) })
        sliders.addView(sliderRow("ĐỘ SÁNG", "brightness", 70, Neon.AMBER, Neon.MAGENTA), LinearLayout.LayoutParams(0, -2, 1f).also { it.leftMargin = dp(5) })
        panel.addView(sliders, LinearLayout.LayoutParams(-1, -2))
        // hàng 2: LÙI (viền hồng) + BÀI TIẾP (nền cyan đặc)
        val btnBack = TextView(host).apply {
            text = "LÙI"; textSize = 14f; gravity = Gravity.CENTER; letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Neon.MAGENTA)
            background = neonBtn(Neon.PANEL, Neon.MAGENTA, mix2(Neon.PANEL, Neon.MAGENTA))
            setOnClickListener { Haptics.tick(this); send(JSONObject().put("action", "back")) }
        }
        val btnNext = TextView(host).apply {
            text = "BÀI TIẾP"; textSize = 14f; gravity = Gravity.CENTER; letterSpacing = 0.08f
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            setTextColor(Neon.INK)
            background = neonBtn(0xFF00C8E0.toInt(), Neon.CYAN, 0xFF0098AD.toInt())
            setOnClickListener { Haptics.tick(this); send(JSONObject().put("action", "media").put("cmd", "next")) }
        }
        val backNext = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL }
        backNext.addView(btnBack, LinearLayout.LayoutParams(0, dp(50), 1f).also { it.rightMargin = dp(4) })
        backNext.addView(btnNext, LinearLayout.LayoutParams(0, dp(50), 1f).also { it.leftMargin = dp(4) })
        panel.addView(backNext, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        // chất lượng video: nhãn bên trái + 3 nút (360p | 480p | 720p)
        val q = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        q.addView(caption("CHẤT LƯỢNG\nVIDEO").also { it.gravity = Gravity.START }, LinearLayout.LayoutParams(dp(LABEL_W), -2))
        btnQ360 = qualityButton(360); btnQ480 = qualityButton(480); btnQ720 = qualityButton(720)
        q.addView(btnQ360, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.rightMargin = dp(3) })
        q.addView(btnQ480, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.leftMargin = dp(3); it.rightMargin = dp(3) })
        q.addView(btnQ720, LinearLayout.LayoutParams(0, dp(40), 1f).also { it.leftMargin = dp(3) })
        panel.addView(q, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        // hàng dưới cùng: lùi 10s, phát/dừng, tiến 10s
        val media = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        fun mediaBtn(kind: Int, cmd: String) = IconButton(host, kind).also { b ->
            b.rect = true
            b.setOnClickListener { send(JSONObject().put("action", "media").put("cmd", cmd)) }
        }
        val mediaBtns = listOf(mediaBtn(IconButton.REW, "rew"), mediaBtn(IconButton.PLAY, "playpause"), mediaBtn(IconButton.FWD, "fwd"))
        for ((i, b) in mediaBtns.withIndex()) {
            media.addView(b, LinearLayout.LayoutParams(0, dp(48), 1f).also {
                if (i > 0) it.leftMargin = dp(4)
                if (i < mediaBtns.size - 1) it.rightMargin = dp(4)
            })
        }
        panel.addView(media, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        root.addView(panel, LinearLayout.LayoutParams(-1, -2))
        refreshQuality()
        return root
    }

    /** Pha 35% màu nhấn vào nền panel, dùng cho trạng thái đang nhấn của nút LÙI. */
    private fun mix2(base: Int, accent: Int): Int = Color.rgb(
        (Color.red(base) * 0.65f + Color.red(accent) * 0.35f).toInt(),
        (Color.green(base) * 0.65f + Color.green(accent) * 0.35f).toInt(),
        (Color.blue(base) * 0.65f + Color.blue(accent) * 0.35f).toInt()
    )

    /** Thanh trượt neon: thẻ tối bo góc; trên là nhãn + số; dưới là đường trượt gradient [c1] -> [c2]. */
    private fun sliderRow(label: String, action: String, initial: Int, c1: Int, c2: Int): View = LinearLayout(host).apply {
        orientation = LinearLayout.VERTICAL
        background = neon(Neon.PANEL, Neon.EDGE, 10f, glow = false)
        setPadding(dp(12), dp(8), dp(10), dp(4))
        val value = TextView(host).apply {
            text = "$initial"; textSize = 13f; setTextColor(c1); gravity = Gravity.END
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        }
        val labelRow = LinearLayout(host).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        labelRow.addView(caption(label).also { it.gravity = Gravity.START }, LinearLayout.LayoutParams(0, -2, 1f))
        labelRow.addView(value, LinearLayout.LayoutParams(-2, -2))
        addView(labelRow, LinearLayout.LayoutParams(-1, -2))
        val trackH = dp(10)
        val bg = GradientDrawable().apply {
            setColor(0xFF05070C.toInt()); cornerRadius = trackH / 2f; setStroke(dp(1), Neon.EDGE_HI); setSize(-1, trackH)
        }
        val fg = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
            cornerRadius = trackH / 2f; setSize(-1, trackH)
        }
        val layers = LayerDrawable(arrayOf<Drawable>(bg, ClipDrawable(fg, Gravity.START, ClipDrawable.HORIZONTAL)))
        layers.setId(0, android.R.id.background); layers.setId(1, android.R.id.progress)
        val knob = GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(Neon.TEXT); setStroke(dp(3), c2); setSize(dp(22), dp(22))
        }
        val sb = SeekBar(host).apply {
            max = 100; progress = initial
            progressDrawable = layers
            thumb = knob
            splitTrack = false
            setPadding(dp(12), 0, dp(12), 0)
            var last = 0L
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    value.text = "$p"
                    if (!fromUser) return
                    val now = SystemClock.uptimeMillis()
                    if (now - last >= 80) {
                        last = now
                        send(JSONObject().put("action", action).put("level", p), warn = false)
                    }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {
                    send(JSONObject().put("action", action).put("level", s.progress))
                }
            })
        }
        addView(sb, LinearLayout.LayoutParams(-1, dp(30)))
    }

    private fun qualityButton(q: Int) = TextView(host).apply {
        text = "${q}p"; textSize = 13f; gravity = Gravity.CENTER; letterSpacing = 0.08f
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        setOnClickListener {
            Haptics.tick(this)
            quality = q
            refreshQuality()
            send(JSONObject().put("action", "quality").put("q", q))
        }
    }

    /** Nút đang chọn: nền cyan đặc chữ đen, có vàng sáng; nút còn lại: nền tối viền xám chữ sáng. */
    private fun refreshQuality() {
        for ((b, q) in listOf(btnQ360 to 360, btnQ480 to 480, btnQ720 to 720)) {
            val on = quality == q
            b.background = if (on) neon(Neon.CYAN, Neon.CYAN, 9f, glow = true) else neon(Neon.PANEL, Neon.EDGE_HI, 9f, glow = false)
            b.setTextColor(if (on) Neon.INK else Neon.TEXT)
        }
    }

    /** Nút khoá cảm ứng: đang khoá thì đảo màu và đổi nhãn thành "BẬT CẢM ỨNG TV". */
    private fun refreshTouchButton() {
        (btnTouch as IconButton).inverted = tvTouchOff
        (touchBox.getChildAt(1) as TextView).text = if (tvTouchOff) "BẬT CẢM\nỨNG TV" else "TẮT CẢM\nỨNG TV"
    }

    /** Nút tắt màn hình: đỏ khi TV đang sáng, xanh khi TV đang tắt. */
    private fun refreshPowerButton() {
        (btnPower as IconButton).tint = if (tvScreenOn) Neon.RED else Neon.GREEN
        (powerBox.getChildAt(1) as TextView).text = if (tvScreenOn) "TẮT\nMÀN HÌNH" else "MỞ\nMÀN HÌNH"
    }

    /** Nút chuyển đổi giao diện: xanh dương = TV, đỏ = YouTube. */
    private fun refreshScreenButton() {
        (btnScreen as IconButton).tint = if (RemoteState.mode == "yt") Neon.RED else Neon.BLUE
        (screenBox.getChildAt(1) as TextView).text = "CHUYỂN ĐỔI\nGIAO DIỆN"
    }

    /** Danh sách nút đang ẩn. Lần đầu mặc định ẩn nút "Tắt cảm ứng TV". */
    private fun hiddenSet(): MutableSet<String> =
        host.getSharedPreferences("remote_ui", Context.MODE_PRIVATE).getStringSet("hidden", null)?.toMutableSet()
            ?: mutableSetOf("touch")

    private fun applyButtonVisibility() {
        val hidden = hiddenSet()
        for ((id, v) in topBoxes) v.visibility = if (id in hidden) View.GONE else View.VISIBLE
    }

    private fun showButtonsDialog() {
        AlertDialog.Builder(host)
            .setTitle("Bật / tắt nút trên thiết bị")
            .setMessage("TẮT: chọn một nút để ẩn khỏi màn hình.\nBẬT: chọn nút đang ẩn để hiện lại đúng vị trí cũ.")
            .setPositiveButton("BẬT") { _, _ -> pickButtons(show = true) }
            .setNegativeButton("TẮT") { _, _ -> pickButtons(show = false) }
            .setNeutralButton("Đóng", null)
            .show()
    }

    private fun pickButtons(show: Boolean) {
        val hidden = hiddenSet()
        val ids = topNames.keys.filter { (it in hidden) == show }
        if (ids.isEmpty()) {
            Toast.makeText(host, if (show) "Không có nút nào đang ẩn" else "Tất cả nút đã bị ẩn", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(host)
            .setTitle(if (show) "Chọn nút để BẬT" else "Chọn nút để TẮT")
            .setItems(ids.map { topNames[it] ?: it }.toTypedArray()) { _, which -> setButtonHidden(ids[which], hide = !show) }
            .setNegativeButton("Hủy", null)
            .show()
    }

    private fun setButtonHidden(id: String, hide: Boolean) {
        val hidden = hiddenSet()
        if (hide) hidden.add(id) else hidden.remove(id)
        host.getSharedPreferences("remote_ui", Context.MODE_PRIVATE).edit().putStringSet("hidden", HashSet(hidden)).apply()
        // ẩn nút khoá cảm ứng khi TV đang bị khoá -> mở khoá luôn, kẻo không còn nút nào để mở
        if (hide && id == "touch" && tvTouchOff) {
            tvTouchOff = false
            send(JSONObject().put("action", "touchlock").put("on", false))
            refreshTouchButton()
        }
        applyButtonVisibility()
        Toast.makeText(host, (topNames[id] ?: id) + if (hide) ": đã ẩn" else ": đã hiện", Toast.LENGTH_SHORT).show()
    }

    /** Hộp thoại đổi mã (vd 654321 -> 123456). OK: lưu mã mới và nối lại ngay bằng mã đó. */
    private fun showCodeDialog() {
        val current = RemoteConfig.savedCode(host)
        val input = EditText(host).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(current)
            setSelection(text.length)
            textSize = 24f
            gravity = Gravity.CENTER
            letterSpacing = 0.25f
            setTextColor(Neon.CYAN)
            setHintTextColor(Neon.DIM)
            hint = "123456"
            typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            filters = arrayOf(android.text.InputFilter.LengthFilter(12))
        }
        val box = FrameLayout(host).apply {
            setPadding(dp(24), dp(12), dp(24), 0)
            addView(input, FrameLayout.LayoutParams(-1, -2))
        }
        val dialog = AlertDialog.Builder(host)
            .setTitle("Đổi mã ghép nối")
            .setMessage("Nhập mã của TV/máy tính cần điều khiển (4–12 chữ số). Mã hiện tại: $current")
            .setView(box)
            .setPositiveButton("OK", null)
            .setNegativeButton("Hủy", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().trim()
                if (!RemoteConfig.isValidCode(code)) {
                    Toast.makeText(host, "Mã phải gồm 4–12 chữ số", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                applyNewCode(code)
                dialog.dismiss()
            }
            input.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        }
        dialog.show()
    }

    private fun applyNewCode(code: String) {
        RemoteConfig.saveCode(host, code)
        controller.restart(code)
        setConnected(false)
        Toast.makeText(host, "Đã đổi mã thành $code — đang kết nối lại…", Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------- nút và kết nối

    private fun send(cmd: JSONObject, warn: Boolean = true) {
        val json = cmd.put("type", "remote").toString()
        sender.execute {
            val n = controller.sendRemoteCommand(json)
            if (n == 0 && warn) {
                val now = System.currentTimeMillis()
                if (now - lastWarnAt > 3000) {
                    lastWarnAt = now
                    ui.post { Toast.makeText(host, "Chưa kết nối với TV", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    private fun setConnected(on: Boolean) {
        connected = on
        val c = if (on) Neon.GREEN else Neon.RED
        statusDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c)
            setStroke(dp(2), (c and 0x00FFFFFF) or 0x55000000)
        }
        statusText.setTextColor(c)
        statusText.text = if (on) "ĐÃ KẾT NỐI" else "CHƯA KẾT NỐI"
    }

    private fun startVoice() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói tên video, tên kênh TV, hoặc “mở TV” / “mở YouTube”")
        }
        try {
            host.startActivityForResult(intent, REQ_VOICE)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(host, "Điện thoại chưa có ứng dụng nhận dạng giọng nói", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- chuột cảm ứng

    private var padView: View? = null

    /** Cho phép vuốt quay lại từ dải bên trái (~22% bề rộng) hoặc từ vùng ngoài ô cảm ứng. */
    fun canSwipeBackFrom(rawX: Float, rawY: Float): Boolean {
        if (rawX < maxOf(dp(48), (resources.displayMetrics.widthPixels * 0.22f).toInt())) return true
        val pv = padView ?: return false
        val loc = IntArray(2)
        pv.getLocationOnScreen(loc)
        return rawY < loc[1] || rawY > loc[1] + pv.height
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouchpad(pad: View) {
        padView = pad
        pad.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x; lastY = e.y; startX = e.x; startY = e.y
                    downAt = e.eventTime; moved = false; multi = false
                    stopFling(); scrollActive = false; sampN = 0; lastMoveT = e.eventTime
                    // báo TV hiện con trỏ ngay lúc chạm (không chờ khi trượt)
                    send(JSONObject().put("action", "move").put("dx", 0).put("dy", 0), warn = false)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multi = true
                    stopFling(); sampN = 0
                    lastY = avgY(e)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount >= 2) {
                        val y = avgY(e)
                        pendScroll += (y - lastY) * SCROLL_GAIN
                        lastY = y
                        scrollActive = true
                        addSample(e.eventTime, y)
                        scheduleFlush()
                    } else if (!multi) {
                        val dx = e.x - lastX
                        val dy = e.y - lastY
                        lastX = e.x; lastY = e.y
                        if (!moved && (abs(e.x - startX) > dp(6) || abs(e.y - startY) > dp(6))) moved = true
                        // gia tốc con trỏ: di chậm = chính xác, vuốt nhanh = bay xa
                        val dt = (e.eventTime - lastMoveT).coerceAtLeast(1L)
                        lastMoveT = e.eventTime
                        val v = kotlin.math.hypot(dx, dy) / dt / resources.displayMetrics.density
                        val t = ((v - ACC_V0) / (ACC_V1 - ACC_V0)).coerceIn(0f, 1f)
                        val g = MOVE_GAIN_SLOW + (MOVE_GAIN_FAST - MOVE_GAIN_SLOW) * t * t * (3f - 2f * t)
                        pendDx += dx * g
                        pendDy += dy * g
                        scheduleFlush()
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (scrollActive && e.pointerCount == 2) startFling(e.eventTime)
                    // còn 1 ngón sau khi nhả ngón thứ 2: không gây nhảy con trỏ
                    val idx = if (e.actionIndex == 0) 1 else 0
                    if (idx < e.pointerCount) { lastX = e.getX(idx); lastY = e.getY(idx) }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved && !multi && e.eventTime - downAt < TAP_MS) {
                        Haptics.tick(pad)
                        send(JSONObject().put("action", "click"))
                    }
                }
            }
            true
        }
    }

    private fun avgY(e: MotionEvent): Float {
        var s = 0f
        for (i in 0 until e.pointerCount) s += e.getY(i)
        return s / e.pointerCount
    }

    /** Gửi ngay cú trượt đầu tiên; các cú sau gộp tối đa ~60 tin/giây để không nghẽn kênh truyền. */
    private fun scheduleFlush() {
        if (flushScheduled) return
        val wait = 16L - (SystemClock.uptimeMillis() - lastFlushAt)
        if (wait <= 0L) { flushNow(); return }
        flushScheduled = true
        ui.postDelayed({ flushScheduled = false; flushNow() }, wait)
    }

    private fun addSample(t: Long, y: Float) {
        if (sampN == sampT.size) {
            for (i in 1 until sampN) { sampT[i - 1] = sampT[i]; sampY[i - 1] = sampY[i] }
            sampN--
        }
        sampT[sampN] = t; sampY[sampN] = y; sampN++
    }

    /** Nhả 2 ngón đang lướt: cuộn tiếp với vận tốc cũ và chậm dần (quán tính như touchpad laptop). */
    private fun startFling(upT: Long) {
        scrollActive = false
        if (sampN < 2 || upT - sampT[sampN - 1] > 80) { sampN = 0; return }
        var i0 = sampN - 1
        while (i0 > 0 && sampT[sampN - 1] - sampT[i0 - 1] <= 90) i0--
        val dt = (sampT[sampN - 1] - sampT[i0]).coerceAtLeast(1L)
        val v = (sampY[sampN - 1] - sampY[i0]) / dt * SCROLL_GAIN
        sampN = 0
        if (kotlin.math.abs(v) < FLING_MIN_V) return
        flingV = v.coerceIn(-FLING_MAX_V, FLING_MAX_V)
        flingLastT = SystemClock.uptimeMillis()
        ui.removeCallbacks(flingStep); ui.post(flingStep)
    }

    private fun stopFling() { flingV = 0f; ui.removeCallbacks(flingStep) }

    private val flingStep = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = (now - flingLastT).coerceIn(1L, 50L)
            flingLastT = now
            pendScroll += flingV * dt
            flingV *= Math.pow(FLING_DECAY.toDouble(), dt / 16.0).toFloat()
            scheduleFlush()
            if (kotlin.math.abs(flingV) >= FLING_STOP_V) ui.postDelayed(this, 16) else flingV = 0f
        }
    }

    private fun flushNow() {
        lastFlushAt = SystemClock.uptimeMillis()
        val mx = pendDx.roundToInt(); val my = pendDy.roundToInt()
        val sc = pendScroll.roundToInt()
        if (mx != 0 || my != 0) {
            pendDx -= mx; pendDy -= my
            send(JSONObject().put("action", "move").put("dx", mx).put("dy", my), warn = false)
        }
        if (sc != 0) {
            pendScroll -= sc
            send(JSONObject().put("action", "scroll").put("dy", sc), warn = false)
        }
    }

    companion object {
        /** Mã yêu cầu nhận dạng giọng nói, MainActivity dùng để chuyển kết quả về. */
        const val REQ_VOICE = 4101
        private const val LABEL_W = 84
        private const val MOVE_GAIN_SLOW = 1.1f
        private const val MOVE_GAIN_FAST = 3.6f
        private const val ACC_V0 = 0.15f
        private const val ACC_V1 = 1.6f
        private const val FLING_MIN_V = 0.25f
        private const val FLING_MAX_V = 6f
        private const val FLING_STOP_V = 0.03f
        private const val FLING_DECAY = 0.94f
        private const val SCROLL_GAIN = 1.0f
        private const val TAP_MS = 250L
        private const val BAR_HOLD_MS = 2000L
    }
}
