package com.dk.home.truyentep

import android.content.Context

/**
 * Trạng thái dùng chung của phần Truyền tệp: một phiên kết nối duy nhất và mã ghép nối.
 * Phiên chạy khi (a) trang Truyền tệp đang hiện ở trạng thái dừng hẳn, hoặc (b) màn hình
 * chụp ảnh / gửi tệp đang hiện. Không có service, không thông báo, không chạy nền.
 */
object TruyenTepHub {
    /** Dùng đúng tên prefs/khóa của bản truyentep gốc để giữ mã ghép nối cũ. */
    const val PREFS_NAME = "home_camera"
    const val KEY_CODE = "fixed_room_code"

    private var appCtx: Context? = null
    private var session: TruyenTepSession? = null
    private var pageOn = false
    private var childCount = 0

    /** Phiên hiện tại (null nếu chưa init). */
    val instance: TruyenTepSession? get() = session

    fun init(ctx: Context) {
        if (session != null) return
        val app = ctx.applicationContext
        appCtx = app
        session = TruyenTepSession(app)
    }

    fun currentCode(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_CODE, null)?.let { return it }
        return newCode(ctx)
    }

    /** Tạo mã 6 số mới, lưu lại và trả về. */
    fun newCode(ctx: Context): String {
        val c = (100000..999999).random().toString()
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_CODE, c).apply()
        return c
    }

    fun setPageOn(on: Boolean) {
        pageOn = on
        sync()
    }

    fun childStarted() {
        childCount++
        sync()
    }

    fun childStopped() {
        childCount = maxOf(0, childCount - 1)
        sync()
    }

    /** Dừng hẳn (nút thoát ở màn hình chụp ảnh). */
    fun stopNow() {
        pageOn = false
        childCount = 0
        session?.stop()
    }

    private fun sync() {
        val s = session ?: return
        val ctx = appCtx ?: return
        val want = pageOn || childCount > 0
        if (want && !s.isRunning()) s.start(currentCode(ctx))
        else if (!want && s.isRunning()) s.stop()
    }
}
