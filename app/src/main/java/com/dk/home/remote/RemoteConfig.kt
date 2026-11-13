package com.dk.home.remote

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions

/** Mã ghép nối và cấu hình Firebase cho trang Điều khiển. */
object RemoteConfig {
    private const val TAG = "RemoteConfig"
    const val PREFS = "remote_control"
    const val KEY_CODE = "pairing_code"
    /** Mã mặc định, trùng với bản Windows (truyentep.html / TiviWidget). */
    const val FIXED_CODE = "654321"

    fun isValidCode(c: String): Boolean = c.length in 4..12 && c.all { it in '0'..'9' }

    fun savedCode(ctx: Context): String {
        val c = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CODE, null)
        return if (c != null && isValidCode(c)) c else FIXED_CODE
    }

    fun saveCode(ctx: Context, code: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CODE, code).apply()
    }

    /** Cùng dự án Firebase với bản Windows, để hai bên tìm thấy nhau. */
    fun ensureFirebase(ctx: Context) {
        try {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApiKey("AIzaSyCPwMKElbsQLjOeSVddGAfjNiHnPHV9b1A")
                    .setApplicationId("1:949360436062:web:c9b5b5d47ee4f76fdce5cf")
                    .setProjectId("qrremod")
                    .setDatabaseUrl("https://qrremod-default-rtdb.asia-southeast1.firebasedatabase.app")
                    .build()
                FirebaseApp.initializeApp(ctx, options)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Không khởi tạo được Firebase", e)
        }
    }
}
