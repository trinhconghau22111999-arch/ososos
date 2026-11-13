package com.dk.home.truyentep

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * Trang "Truyền tệp" (chép từ repo truyentep): chọn tệp trên điện thoại rồi gửi sang máy tính
 * đang chạy Qrtuxa (nhập đúng mã ghép nối). Chỉ kết nối khi trang đang mở, không chạy nền.
 */
class TruyenTepPage(private val act: Activity) : LinearLayout(act), TruyenTepSession.Listener {

    companion object {
        /** Mã yêu cầu của hộp chọn tệp; MainActivity chuyển kết quả về [onPicked]. */
        const val REQ_PICK = 7501
        private const val PREFS = "truyentep_page"
        private const val KEY_CODE = "room_code"
        private const val BG = 0xFF0D1224
        private const val CYAN = 0xFF00E5FF
        private const val DIM = 0xFF7F8BC0
    }

    private val session = TruyenTepSession(act)
    private val uris = mutableListOf<Uri>()
    private var sending = false

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private val titleView = TextView(act).apply {
        text = "TRUYỀN TỆP"
        setTextColor(CYAN.toInt())
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }
    private val codeView = TextView(act).apply {
        textSize = 30f
        setTextColor(Color.WHITE)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(0, dp(12), 0, dp(4))
    }
    private val hintView = TextView(act).apply {
        text = "Nhập mã này trong Qrtuxa trên máy tính"
        setTextColor(DIM.toInt())
        textSize = 13f
        gravity = Gravity.CENTER
    }
    private val statusView = TextView(act).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(10), 0, dp(10))
    }
    private val newCodeBtn = Button(act).apply {
        text = "Đổi mã mới"
        setOnClickListener { newCode() }
    }
    private val pickBtn = Button(act).apply {
        text = "Chọn tệp"
        setOnClickListener { pick() }
    }
    private val filesView = TextView(act).apply {
        text = "Chưa chọn tệp nào"
        setTextColor(DIM.toInt())
        textSize = 13f
        setPadding(0, dp(8), 0, dp(8))
    }
    private val sendBtn = Button(act).apply {
        text = "Gửi"
        visibility = View.GONE
        setOnClickListener { sendAll() }
    }
    private val progress = ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        visibility = View.GONE
    }
    private val sendStatus = TextView(act).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        visibility = View.GONE
        setPadding(0, dp(6), 0, 0)
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(BG.toInt())
        setPadding(dp(16), dp(16), dp(16), dp(16))
        addView(titleView, LinearLayout.LayoutParams(-1, -2))
        addView(codeView, LinearLayout.LayoutParams(-1, -2))
        addView(hintView, LinearLayout.LayoutParams(-1, -2))
        addView(statusView, LinearLayout.LayoutParams(-1, -2))
        addView(newCodeBtn, LinearLayout.LayoutParams(-1, -2))
        addView(pickBtn, LinearLayout.LayoutParams(-1, -2))
        addView(filesView, LinearLayout.LayoutParams(-1, -2))
        addView(sendBtn, LinearLayout.LayoutParams(-1, -2))
        addView(progress, LinearLayout.LayoutParams(-1, -2))
        addView(sendStatus, LinearLayout.LayoutParams(-1, -2))
        session.listener = this
        codeView.text = currentCode()
        renderStatus(0)
    }

    private fun currentCode(): String {
        val prefs = act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
        prefs.getString(KEY_CODE, null)?.let { return it }
        val c = (100000..999999).random().toString()
        prefs.edit().putString(KEY_CODE, c).apply()
        return c
    }

    /** Gọi từ MainActivity: chỉ kết nối khi trang đang hiện và đã dừng hẳn ở trang này. */
    fun setActive(on: Boolean) {
        if (on) {
            if (!session.isRunning()) session.start(currentCode())
            codeView.text = currentCode()
        } else {
            if (session.isRunning()) session.stop()
        }
        renderStatus(session.connectedCount())
    }

    override fun onConnectionChanged(connected: Int) = renderStatus(connected)

    private fun renderStatus(connected: Int) {
        statusView.text = when {
            connected > 0 -> "Đã kết nối: $connected máy tính"
            session.isRunning() -> "Đang chờ máy tính (Qrtuxa) nhập mã..."
            else -> "Mở trang này để bắt đầu nhận kết nối"
        }
        newCodeBtn.isEnabled = connected == 0 && !sending
    }

    private fun newCode() {
        if (sending || session.connectedCount() > 0) {
            Toast.makeText(act, "Không đổi mã khi đang có máy tính kết nối", Toast.LENGTH_SHORT).show()
            return
        }
        val c = (100000..999999).random().toString()
        act.getSharedPreferences(PREFS, Activity.MODE_PRIVATE).edit().putString(KEY_CODE, c).apply()
        codeView.text = c
        if (session.isRunning()) session.changeRoomCode(c)
        Toast.makeText(act, "Đã đổi sang mã mới: $c", Toast.LENGTH_SHORT).show()
    }

    private fun pick() {
        if (sending) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try {
            act.startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            Toast.makeText(act, "Không mở được trình chọn tệp", Toast.LENGTH_SHORT).show()
        }
    }

    /** MainActivity chuyển kết quả chọn tệp về đây. */
    fun onPicked(data: Intent?) {
        if (data == null) return
        val list = mutableListOf<Uri>()
        data.clipData?.let { cd ->
            for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { list.add(it) }
        }
        if (list.isEmpty()) data.data?.let { list.add(it) }
        if (list.isEmpty()) return
        uris.clear()
        uris.addAll(list)
        for (u in list) {
            try {
                act.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // một số nguồn không hỗ trợ quyền lâu dài - bỏ qua
            }
        }
        renderFiles()
    }

    private fun displayName(uri: Uri): String? = try {
        act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (e: Exception) {
        null
    }

    private fun renderFiles() {
        if (uris.isEmpty()) {
            filesView.text = "Chưa chọn tệp nào"
            sendBtn.visibility = View.GONE
            return
        }
        filesView.text = uris.mapIndexed { i, u -> "• " + (displayName(u) ?: "tệp ${i + 1}") }
            .joinToString("\n")
        sendBtn.visibility = View.VISIBLE
        sendBtn.isEnabled = !sending
    }

    private fun sendAll() {
        if (sending || uris.isEmpty()) return
        if (session.connectedCount() == 0) {
            Toast.makeText(act, "Chưa có máy tính nào đang kết nối", Toast.LENGTH_SHORT).show()
            return
        }
        sending = true
        pickBtn.isEnabled = false
        sendBtn.isEnabled = false
        newCodeBtn.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.progress = 0
        sendStatus.visibility = View.VISIBLE
        sendFileAt(0)
    }

    /** Gửi lần lượt từng tệp (không gửi song song, để máy tính nhận từng tệp một). */
    private fun sendFileAt(index: Int) {
        if (index >= uris.size) {
            sendStatus.text = "Đã gửi xong ${uris.size} tệp"
            finishSending()
            return
        }
        val uri = uris[index]
        val name = displayName(uri) ?: "tep_$index"
        val total = uris.size
        sendStatus.text = "Đang gửi (${index + 1}/$total): $name"
        val base = index * 100 / total
        progress.progress = base

        Thread {
            val bytes = try {
                act.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            // Đọc xong thì quay về luồng chính để gọi session (cùng luồng với callback kết quả)
            act.runOnUiThread {
                if (bytes == null) {
                    Toast.makeText(act, "Không đọc được: $name", Toast.LENGTH_SHORT).show()
                    sendFileAt(index + 1)
                    return@runOnUiThread
                }
                val mime = act.contentResolver.getType(uri) ?: "application/octet-stream"
                var best = 0
                var expected = 0
                var finished = 0
                var anySuccess = false
                expected = session.sendFileToAll(
                    bytes, name, mime,
                    onProgress = { p ->
                        if (p > best) {
                            best = p
                            progress.progress = base + p / total
                        }
                    },
                    onResult = { ok ->
                        finished++
                        if (ok) anySuccess = true
                        if (finished >= expected) {
                            if (anySuccess) {
                                progress.progress = (index + 1) * 100 / total
                                sendFileAt(index + 1)
                            } else {
                                sendStatus.text = "Đã hủy gửi (mất kết nối giữa chừng)"
                                finishSending()
                            }
                        }
                    }
                )
                if (expected == 0) {
                    Toast.makeText(act, "Chưa có máy tính nào đang kết nối - đã hủy gửi", Toast.LENGTH_LONG).show()
                    sendStatus.text = "Đã hủy: chưa có máy tính kết nối"
                    finishSending()
                }
            }
        }.start()
    }

    private fun finishSending() {
        sending = false
        progress.visibility = View.GONE
        pickBtn.isEnabled = true
        sendBtn.visibility = View.GONE
        renderStatus(session.connectedCount())
    }
}
