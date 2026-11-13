package com.dk.home

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Nút AI giọng nói (học từ repo nokia_cuibap): bấm nút AI ở khung đồng hồ -> nói lệnh -> app hiểu và làm.
 * Phân tích lệnh dùng chung AiParser / AiMatch với nokia_cuibap; nhận dạng giọng nói dùng VoiceInput.
 */
class AiAssistant(private val act: MainActivity) {
    private val voice = VoiceInput(act)
    private val main = Handler(Looper.getMainLooper())

    private var dialog: Dialog? = null
    private var heardTv: TextView? = null
    private var levelTv: TextView? = null
    private var listening = false

    private class Person(val name: String, val number: String)

    private fun dp(v: Int) = Math.round(v * act.resources.displayMetrics.density)
    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()
    private fun granted(p: String) =
        Build.VERSION.SDK_INT < 23 || act.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    // ---------------------------------------------------------------- quyền
    private var permCode = 5100
    private val permWait = HashMap<Int, Pair<() -> Unit, () -> Unit>>()

    private fun needPerm(perm: String, onDenied: () -> Unit = {}, onOk: () -> Unit) {
        if (granted(perm)) { onOk(); return }
        val code = permCode++
        permWait[code] = Pair(onOk, onDenied)
        act.requestPermissions(arrayOf(perm), code)
    }

    /** MainActivity chuyển kết quả xin quyền sang đây. Trả về true nếu là yêu cầu của AI. */
    fun onPermissionResult(code: Int, results: IntArray): Boolean {
        val w = permWait.remove(code) ?: return false
        if (results.isNotEmpty() && results[0] == PackageManager.PERMISSION_GRANTED) w.first() else w.second()
        return true
    }

    // ---------------------------------------------------------------- nghe
    fun start() {
        if (listening) return
        if (!voice.available()) { toast("Máy chưa có dịch vụ nhận dạng giọng nói (cần app Google)"); return }
        needPerm(Manifest.permission.RECORD_AUDIO, { toast("Chưa cấp quyền Micro cho app") }) { listen() }
    }

    private fun listen() {
        showListenDialog()
        listening = true
        voice.start(
            onPartial = { heardTv?.text = it },
            onLevel = { lv ->
                val n = (((lv + 2f) / 12f).coerceIn(0f, 1f) * 10f).toInt()
                levelTv?.text = "█".repeat(n) + "░".repeat(10 - n)
            },
            onResult = { list -> if (listening) { stopListen(); handle(list) } },
            onError = { code -> if (listening) { stopListen(); toast(aiErrorText(code)) } }
        )
    }

    private fun stopListen() {
        listening = false
        try { dialog?.dismiss() } catch (_: Throwable) {}
        dialog = null
    }

    /** Hủy mọi thứ đang chạy (gọi khi app xuống nền / đóng). */
    fun cancel() {
        if (listening) { listening = false; voice.cancel() }
        try { dialog?.dismiss() } catch (_: Throwable) {}
        dialog = null
    }

    private fun label(t: CharSequence, size: Float, color: Int, bold: Boolean = true) = TextView(act).apply {
        text = t; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif-condensed", if (bold) Typeface.BOLD else Typeface.NORMAL)
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
    }

    private fun showListenDialog() {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(14))
        }
        box.addView(label("●  ĐANG NGHE...", 20f, Ui.CYAN))
        levelTv = label("░░░░░░░░░░", 18f, Ui.CYAN).also { box.addView(it) }
        heardTv = label("Hãy nói lệnh của bạn", 16f, Color.WHITE).also {
            it.setPadding(0, dp(12), 0, dp(12)); it.minLines = 2; box.addView(it)
        }
        val row = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val doneBtn = label("XONG", 14f, Ui.CYAN).apply {
            setPadding(dp(22), dp(10), dp(22), dp(10)); background = Ui.panel(act, Ui.ACCENT, 8f)
            setOnClickListener { voice.stop() }
        }
        val cancelBtn = label("HỦY", 14f, Ui.PINK).apply {
            setPadding(dp(22), dp(10), dp(22), dp(10)); background = Ui.panel(act, Ui.HOT, 8f)
            setOnClickListener { this@AiAssistant.cancel() }
        }
        row.addView(doneBtn); row.addView(cancelBtn, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(14) })
        box.addView(row)
        val d = AlertDialog.Builder(act).setView(box).create()
        d.window?.setBackgroundDrawable(Ui.panel(act, Ui.GRID))
        d.setOnCancelListener { if (listening) { listening = false; voice.cancel() } }
        d.show()
        dialog = d
    }

    // ---------------------------------------------------------------- hiểu lệnh
    private fun handle(list: List<String>) {
        val heard = list.filter { it.isNotBlank() }
        if (heard.isEmpty()) { toast("Không nghe rõ. Bấm AI để thử lại."); return }
        val parsed = heard.map { it to AiParser.parse(it) }
        val best = parsed.firstOrNull { it.second !is AiCmd.Unknown } ?: parsed.first()
        var cmd = best.second
        // Nói thẳng tên kênh/đài ("HTV9", "Cà Mau") mà không nói "TV" vẫn mở đúng kênh
        if (cmd is AiCmd.Unknown || cmd is AiCmd.OpenApp)
            heard.firstNotNullOfOrNull { TvChannels.find(act, it) }?.let { cmd = AiCmd.Tv(it) }
        exec(cmd, best.first)
    }

    private fun exec(c: AiCmd, heard: String) {
        when (c) {
            is AiCmd.Call -> pickPerson(c.target, c.number) { p -> confirmCall(p) }
            is AiCmd.Sms -> pickPerson(c.target, c.number) { p ->
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(p.number))).putExtra("sms_body", c.body)
                if (!act.launchFirst(listOf(i))) toast("Không mở được ứng dụng Tin nhắn")
            }
            is AiCmd.Camera -> {
                val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                if (c.selfie) i.putExtra("android.intent.extras.CAMERA_FACING", 1)
                    .putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                    .putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
                if (!act.launchFirst(listOf(i))) toast("Không mở được máy ảnh")
            }
            is AiCmd.Gallery -> openCat(Intent.CATEGORY_APP_GALLERY, "Thư viện")
            is AiCmd.Music -> {
                val i = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                    .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                    .putExtra(android.app.SearchManager.QUERY, c.query)
                val m = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC)
                if (c.query.isNotBlank()) { if (!act.launchFirst(listOf(i))) act.aiYoutube(c.query) }
                else if (!act.launchFirst(listOf(m))) act.aiYoutube("")
            }
            is AiCmd.Open -> openId(c.id)
            is AiCmd.OpenApp -> openApp(c.name)
            is AiCmd.YouTube -> act.aiYoutube(c.query)
            is AiCmd.Tv -> act.aiTv(c.query)
            is AiCmd.Volume -> {
                val am = act.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
                val d = if (c.up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, 0)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI)
                toast(if (c.up) "Đã tăng âm lượng" else "Đã giảm âm lượng")
            }
            is AiCmd.Torch -> toast(
                if (Torch.set(act, c.on)) (if (c.on) "Đã bật đèn pin" else "Đã tắt đèn pin")
                else "Không bật được đèn pin (máy không có flash hoặc camera đang bận)")
            is AiCmd.Bluetooth -> setBluetooth(c.on)
            is AiCmd.SetAlarm -> {
                val i = Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, c.h).putExtra(AlarmClock.EXTRA_MINUTES, c.m)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Hậu OS").putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (act.launchFirst(listOf(i))) toast("Đã đặt báo thức %02d:%02d".format(c.h, c.m))
                else toast("Không đặt được báo thức (máy không có ứng dụng Đồng hồ)")
            }
            is AiCmd.ClearAlarms -> {
                toast("Android không cho app xóa báo thức của app Đồng hồ.\nMình mở danh sách để bạn xóa.")
                act.openClockApp()
            }
            is AiCmd.Lock -> toast("Hậu OS chưa hỗ trợ khóa màn hình bằng giọng nói")
            is AiCmd.Time -> { val cal = Calendar.getInstance()
                toast("Bây giờ là ${cal.get(Calendar.HOUR_OF_DAY)} giờ ${cal.get(Calendar.MINUTE)} phút") }
            is AiCmd.Date -> toast("Hôm nay là " + SimpleDateFormat("EEEE, d MMMM yyyy", Locale("vi")).format(Date()))
            is AiCmd.Help -> showHelp()
            is AiCmd.Cancel -> {}
            else -> toast("Chưa hiểu lệnh: \"$heard\".\nHãy nói rõ hơn, ví dụ: \"gọi cho mẹ\", \"mở Zalo\".")
        }
    }

    // ---------------------------------------------------------------- thực hiện
    private fun openCat(cat: String, name: String) {
        if (!act.launchFirst(listOf(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, cat)))) toast("Không mở được $name")
    }

    private fun openId(id: String) {
        when (id) {
            "youtube" -> act.aiYoutube("")
            "tv" -> act.aiTv("")
            "remote" -> act.aiPage(-3f)
            "home", "apps" -> act.aiPage(0f)
            "clock" -> act.openClockApp()
            "calendar" -> act.openCalendarApp()
            "calllog" -> act.launchFirst(listOf(Intent(Intent.ACTION_VIEW, CallLog.Calls.CONTENT_URI), Intent(Intent.ACTION_DIAL)))
            "contacts" -> act.launchFirst(listOf(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)))
            "messages" -> openCat(Intent.CATEGORY_APP_MESSAGING, "Tin nhắn")
            "music" -> openCat(Intent.CATEGORY_APP_MUSIC, "Nhạc")
            "calc" -> openCat(Intent.CATEGORY_APP_CALCULATOR, "Máy tính")
            "recorder" -> act.launchFirst(listOf(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION)))
            "settings" -> act.launchFirst(listOf(Intent(Settings.ACTION_SETTINGS)))
            "wifi" -> act.launchFirst(listOf(Intent(Settings.ACTION_WIFI_SETTINGS)))
            else -> toast("Hậu OS chưa có mục \"$id\"")
        }
    }

    private fun openApp(name: String) {
        val pm = act.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .filter { it.activityInfo.packageName != act.packageName }
            .distinctBy { it.activityInfo.packageName }
        val idx = AiMatch.rank(apps.map { aiNorm(it.loadLabel(pm).toString()) }, name)
        if (idx.isEmpty()) { toast("Không thấy ứng dụng \"$name\""); return }
        val a = apps[idx[0]].activityInfo
        act.launchFirst(listOf(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setClassName(a.packageName, a.name)))
    }

    private fun setBluetooth(on: Boolean) {
        val ad = try { BluetoothAdapter.getDefaultAdapter() } catch (_: Throwable) { null }
        if (ad == null) { toast("Máy không có Bluetooth"); return }
        if (ad.isEnabled == on) { toast("Bluetooth đã " + (if (on) "bật" else "tắt") + " rồi"); return }
        val ok = try { if (on) ad.enable() else ad.disable() } catch (_: Throwable) { false }
        if (ok) toast("Đang " + (if (on) "bật" else "tắt") + " Bluetooth")
        else act.launchFirst(listOf(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)))
    }

    private fun loadContacts(): List<Person> = try {
        val out = ArrayList<Person>()
        act.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(Person(c.getString(0) ?: "", c.getString(1) ?: ""))
        }
        out
    } catch (_: Throwable) { emptyList() }

    /** Tìm người theo tên (hoặc dùng luôn số đọc ra); nhiều người khớp thì cho chọn. */
    private fun pickPerson(target: String, number: String, then: (Person) -> Unit) {
        if (number.isNotEmpty()) { then(Person(number, number)); return }
        needPerm(Manifest.permission.READ_CONTACTS, { toast("Cần quyền Danh bạ để tìm \"$target\"") }) {
            Thread {
                val all = loadContacts()
                val idx = AiMatch.rank(all.map { aiNorm(it.name) }, target)
                val found = idx.map { all[it] }.distinctBy { it.number.filter { ch -> ch.isDigit() } }.take(6)
                main.post {
                    when {
                        found.isEmpty() -> toast("Không thấy \"$target\" trong danh bạ")
                        found.size == 1 -> then(found[0])
                        else -> AlertDialog.Builder(act).setTitle("Chọn người")
                            .setItems(found.map { "${it.name}  ${it.number}" }.toTypedArray()) { _, i -> then(found[i]) }
                            .setNegativeButton("Hủy", null).show()
                    }
                }
            }.start()
        }
    }

    private fun confirmCall(p: Person) {
        AlertDialog.Builder(act).setTitle("Gọi cho " + p.name + "?").setMessage(p.number)
            .setPositiveButton("Gọi") { _, _ -> callNow(p.number) }
            .setNegativeButton("Hủy", null).show()
    }

    private fun callNow(number: String) {
        val uri = Uri.parse("tel:" + Uri.encode(number))
        needPerm(Manifest.permission.CALL_PHONE, { act.launchFirst(listOf(Intent(Intent.ACTION_DIAL, uri))) }) {
            if (!act.launchFirst(listOf(Intent(Intent.ACTION_CALL, uri)))) toast("Không gọi được")
        }
    }

    private fun showHelp() {
        val tips = listOf(
            "Gọi cho mẹ", "Nhắn tin cho Nam nội dung tối nay họp", "Chụp ảnh / chụp ảnh tự sướng",
            "Mở Zalo / mở Facebook / mở Chrome", "Mở TV / xem kênh HTV9 / đài Cà Mau", "Mở YouTube / xem video Sơn Tùng",
            "Phát nhạc của Sơn Tùng", "Đặt báo thức 6 giờ 30 / sau 20 phút", "Bật đèn pin / tăng âm lượng",
            "Bật Bluetooth", "Mở danh bạ / tin nhắn / lịch", "Bây giờ là mấy giờ?")
        AlertDialog.Builder(act).setTitle("AI giọng nói - bạn có thể nói:")
            .setMessage(tips.joinToString("\n") { "• $it" }).setPositiveButton("Đóng", null).show()
    }
}
