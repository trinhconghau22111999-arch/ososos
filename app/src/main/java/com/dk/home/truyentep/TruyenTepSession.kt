package com.dk.home.truyentep

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import com.dk.home.remote.RemoteConfig
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

private const val TAG = "TruyenTepSession"
/** Số máy tính tối đa cùng lúc (giữ như bản truyentep gốc). */
const val MAX_VIEWERS_PER_CAMERA = 4
private const val BASE_RECONNECT_DELAY_MS = 2000L
private const val MAX_RECONNECT_DELAY_MS = 30_000L

/** Một máy tính (Qrtuxa) đang kết nối với phiên gửi tệp. */
private class ViewerConn(val viewerId: String) {
    var sig: SignalingClient? = null
    var pcm: PeerConnectionManager? = null
    var reconnectAttempt = 0
    var removed = false
    var reconnectRunnable: Runnable? = null
}

/**
 * Phiên gửi tệp sang máy tính (logic chép từ CameraStreamService của repo truyentep).
 * Khác bản gốc: KHÔNG chạy như foreground service, KHÔNG thông báo, KHÔNG giữ wake lock.
 * Chỉ chạy khi trang Truyền tệp đang mở; trang đóng thì [stop] ngắt hết kết nối.
 */
class TruyenTepSession(private val ctx: Context) {

    interface Listener {
        /** Gọi trên luồng chính mỗi khi số máy tính kết nối thay đổi. */
        fun onConnectionChanged(connected: Int)
    }

    var listener: Listener? = null

    /** Kiểu lắng nghe của bản gốc (màn hình chụp ảnh dùng để hiện trạng thái kết nối). */
    interface ConnectionStatusListener {
        fun onConnectionStatusChanged(connectedCount: Int, maxViewers: Int)
    }
    private val statusListeners = java.util.concurrent.CopyOnWriteArrayList<ConnectionStatusListener>()

    fun addConnectionStatusListener(l: ConnectionStatusListener) {
        statusListeners.add(l)
        l.onConnectionStatusChanged(connectedCount(), MAX_VIEWERS_PER_CAMERA)
    }

    fun removeConnectionStatusListener(l: ConnectionStatusListener) {
        statusListeners.remove(l)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val viewerConns = LinkedHashMap<String, ViewerConn>()
    private var viewersRef: DatabaseReference? = null
    private var viewersListener: ChildEventListener? = null
    private val eglBase: EglBase by lazy { EglBase.create() }
    private var peerFactory: PeerConnectionFactory? = null
    private var roomCode: String? = null
    private var running = false

    fun isRunning(): Boolean = running

    /** Số máy tính đã kết nối xong (không tính máy đang nối lại). */
    fun connectedCount(): Int = viewerConns.values.count { it.pcm != null && it.reconnectAttempt == 0 }

    private fun notifyStatus() {
        val n = connectedCount()
        handler.post {
            listener?.onConnectionChanged(n)
            statusListeners.forEach { it.onConnectionStatusChanged(n, MAX_VIEWERS_PER_CAMERA) }
        }
    }

    /** Bắt đầu nhận kết nối cho mã [code]. Gọi trên luồng chính. */
    fun start(code: String) {
        if (running) return
        running = true
        roomCode = code
        RemoteConfig.ensureFirebase(ctx)
        if (peerFactory == null) peerFactory = PeerConnectionManager.createFactory(ctx, eglBase)
        FirebaseDatabase.getInstance().reference.child("rooms").child(code)
            .updateChildren(mapOf("status" to "waiting", "consentGivenAt" to System.currentTimeMillis()))
        watchViewers(code)
        notifyStatus()
    }

    /** Dừng phiên: ngắt mọi kết nối và báo phòng đã đóng trên Firebase. */
    fun stop() {
        if (!running) return
        running = false
        val code = roomCode
        cleanup()
        roomCode = null
        if (code != null) {
            val room = FirebaseDatabase.getInstance().reference.child("rooms").child(code)
            room.child("status").setValue("ended")
            room.child("viewers").removeValue()
        }
        notifyStatus()
    }

    /** Đổi sang mã mới khi đang chạy (không có máy tính nào đang kết nối). */
    fun changeRoomCode(newCode: String) {
        val old = roomCode
        cleanup()
        val root = FirebaseDatabase.getInstance().reference.child("rooms")
        if (old != null && old != newCode) {
            root.child(old).child("status").setValue("ended")
            root.child(old).child("viewers").removeValue()
        }
        roomCode = newCode
        root.child(newCode).updateChildren(
            mapOf("status" to "waiting", "consentGivenAt" to System.currentTimeMillis())
        )
        watchViewers(newCode)
        notifyStatus()
    }

    private fun cleanup() {
        for (c in viewerConns.values) {
            c.removed = true
            c.reconnectRunnable?.let { handler.removeCallbacks(it) }
            c.pcm?.release()
            c.pcm = null
            c.sig?.release()
            c.sig = null
        }
        viewerConns.clear()
        viewersListener?.let { viewersRef?.removeEventListener(it) }
        viewersListener = null
        viewersRef = null
    }

    /** Theo dõi rooms/{code}/viewers: mỗi máy tính mới được cấp 1 kênh riêng (chỉ DataChannel). */
    private fun watchViewers(code: String) {
        val ref = FirebaseDatabase.getInstance().reference.child("rooms").child(code).child("viewers")
        viewersRef = ref
        val l = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prevKey: String?) {
                val viewerId = snapshot.key ?: return
                if (viewerConns.containsKey(viewerId)) return
                if (!snapshot.hasChild("present")) {
                    // node rác (máy tính đã tắt đột ngột) - dọn, không cấp slot
                    snapshot.ref.removeValue()
                    return
                }
                if (viewerConns.size >= MAX_VIEWERS_PER_CAMERA) return
                val conn = ViewerConn(viewerId)
                conn.reconnectRunnable = Runnable { if (!conn.removed) connectViewer(code, conn) }
                viewerConns[viewerId] = conn
                connectViewer(code, conn)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val viewerId = snapshot.key ?: return
                val conn = viewerConns.remove(viewerId) ?: return
                conn.removed = true
                conn.reconnectRunnable?.let { handler.removeCallbacks(it) }
                conn.pcm?.release()
                conn.pcm = null
                conn.sig?.release()
                conn.sig = null
                notifyStatus()
            }

            override fun onChildChanged(snapshot: DataSnapshot, prevKey: String?) {}
            override fun onChildMoved(snapshot: DataSnapshot, prevKey: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Mất quyền đọc rooms/$code/viewers: ${error.code} ${error.message}")
            }
        }
        viewersListener = l
        ref.addChildEventListener(l)
    }

    private fun connectViewer(code: String, conn: ViewerConn) {
        if (!running || conn.removed) return
        val factory = peerFactory ?: return

        var sigRef: SignalingClient? = null
        var pcmRef: PeerConnectionManager? = null
        val sig = SignalingClient(
            roomCode = code, viewerId = conn.viewerId, isHost = true,
            listener = object : SignalingClient.Listener {
                override fun onOfferReceived(sdp: String) {}
                override fun onAnswerReceived(sdp: String) { conn.pcm?.handleAnswer(sdp) }
                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    conn.pcm?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }
                override fun onRemoteDisconnected() {
                    if (conn.sig !== sigRef) return
                    scheduleReconnect(code, conn)
                }
            }
        )
        sigRef = sig
        conn.sig = sig

        val pcm = PeerConnectionManager(
            factory = factory, isHost = true, signalingClient = sig, remoteSink = null,
            onConnected = {
                conn.reconnectAttempt = 0
                conn.reconnectRunnable?.let { handler.removeCallbacks(it) }
                notifyStatus()
            },
            onDisconnected = {
                if (conn.pcm === pcmRef) scheduleReconnect(code, conn)
            },
            onFileReceived = { name, mimeType, bytes -> saveReceivedFile(name, mimeType, bytes) }
        )
        pcmRef = pcm
        conn.pcm = pcm
        pcm.init()
        pcm.startFileTransferOffer()
        notifyStatus()
    }

    /** Backoff riêng cho từng máy tính: 1 máy rớt mạng không ảnh hưởng máy khác. */
    private fun scheduleReconnect(code: String, conn: ViewerConn) {
        if (!running || conn.removed) return
        conn.pcm?.release()
        conn.pcm = null
        conn.sig?.release()
        conn.sig = null
        conn.reconnectRunnable?.let { handler.removeCallbacks(it) }
        val delay = minOf(
            BASE_RECONNECT_DELAY_MS * (1 shl conn.reconnectAttempt.coerceAtMost(5)),
            MAX_RECONNECT_DELAY_MS
        )
        conn.reconnectAttempt++
        conn.reconnectRunnable?.let { handler.postDelayed(it, delay) }
        notifyStatus()
    }

    /**
     * Gửi 1 tệp cho mọi máy tính đang kết nối. Gọi trên luồng chính.
     * [onProgress] và [onResult] được gọi lại trên luồng chính. Trả về số máy tính đã nhận lệnh gửi.
     */
    fun sendFileToAll(
        bytes: ByteArray, name: String, mime: String,
        onProgress: (Int) -> Unit, onResult: (Boolean) -> Unit
    ): Int {
        var sent = 0
        for (conn in viewerConns.values.toList()) {
            val pcm = conn.pcm ?: continue
            if (!pcm.isReadyToSendFile()) continue
            sent++
            Thread {
                pcm.sendFile(bytes, name, mime,
                    onProgress = { p -> handler.post { onProgress(p) } },
                    onResult = { ok -> handler.post { onResult(ok) } })
            }.start()
        }
        return sent
    }

    /** Tên hàm như bản gốc: gửi 1 tệp cho mọi máy tính (viewerId không còn cần, truyền rỗng). */
    fun sendFileToAllViewers(
        fileBytes: ByteArray, fileName: String, mimeType: String,
        onProgress: (viewerId: String, percent: Int) -> Unit = { _, _ -> },
        onAnyResult: (viewerId: String, success: Boolean) -> Unit = { _, _ -> }
    ): Int = sendFileToAll(
        fileBytes, fileName, mimeType,
        onProgress = { onProgress("", it) },
        onResult = { onAnyResult("", it) }
    )

    /** Gửi 1 thao tác sửa chữ (xóa del ký tự trước con trỏ, chèn ins) cho các máy tính đang kết nối. */
    fun sendTextEditToAllViewers(del: Int, ins: String): Int {
        val targets = viewerConns.values.mapNotNull { it.pcm }.filter { it.isReadyToSendFile() }
        if (targets.isEmpty()) return 0
        val chunks = splitTextForSending(ins, 3000)
        var okCount = 0
        for (pcm in targets) {
            var allSent = true
            for ((idx, part) in chunks.withIndex()) {
                val json = org.json.JSONObject().apply {
                    put("type", "text-edit")
                    put("del", if (idx == 0) del else 0)
                    put("ins", part)
                }.toString()
                if (!pcm.sendJsonMessage(json)) { allSent = false; break }
            }
            if (allSent) okCount++
        }
        return okCount
    }

    /** Tách chuỗi thành các đoạn <= maxLen ký tự, không cắt đôi cặp ký tự thay thế (emoji). */
    private fun splitTextForSending(text: String, maxLen: Int): List<String> {
        if (text.length <= maxLen) return listOf(text)
        val out = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var end = minOf(i + maxLen, text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            out.add(text.substring(i, end))
            i = end
        }
        return out
    }

    /** Lưu tệp nhận từ máy tính vào Download/QrTruyenTep (như bản gốc). */
    private fun saveReceivedFile(name: String, mimeType: String, bytes: ByteArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QrTruyenTep")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                ctx.contentResolver.update(uri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = java.io.File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "QrTruyenTep"
                )
                if (!dir.exists()) dir.mkdirs()
                java.io.File(dir, name).writeBytes(bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lưu tệp nhận được thất bại: ${e.message}", e)
        }
    }
}
