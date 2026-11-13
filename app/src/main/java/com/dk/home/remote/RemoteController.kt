package com.dk.home.remote

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.dk.home.remote.webrtc.PeerConnectionManager
import com.dk.home.remote.webrtc.SignalingClient
import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

private const val TAG = "RemoteController"

/**
 * Kết nối điều khiển. Chỉ chạy khi trang Điều khiển đang mở: [start] khi mở trang, [stop] khi rời trang.
 * Không có service nền và không tự chạy khi khởi động máy.
 *
 * Theo dõi rooms/{mã}/viewers trên Firebase; mỗi máy TV/máy tính có mặt ("present") được cấp một
 * kênh WebRTC riêng, và lệnh điều khiển gửi qua DataChannel của các kênh đó.
 */
class RemoteController(private val appContext: Context) {

    interface StatusListener {
        fun onConnectionStatusChanged(connectedCount: Int)
    }

    @Volatile
    var statusListener: StatusListener? = null

    private class ViewerConn(val viewerId: String) {
        var sig: SignalingClient? = null
        var pcm: PeerConnectionManager? = null
        @Volatile var connected = false
        var attempt = 0
        var removed = false
        var reconnect: Runnable? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val conns = LinkedHashMap<String, ViewerConn>()
    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var code: String = ""
    private var viewersRef: DatabaseReference? = null
    private var viewersListener: ChildEventListener? = null
    @Volatile
    private var running = false

    /** Bắt đầu kết nối với mã ghép nối [pairCode]. */
    fun start(pairCode: String) {
        synchronized(lock) {
            if (running) return
            running = true
            code = pairCode
        }
        RemoteConfig.ensureFirebase(appContext)
        val egl = EglBase.create()
        eglBase = egl
        factory = PeerConnectionManager.createFactory(appContext, egl)

        val roomRef = FirebaseDatabase.getInstance().reference.child("rooms").child(code)
        roomRef.updateChildren(mapOf("status" to "waiting", "consentGivenAt" to System.currentTimeMillis()))

        val ref = roomRef.child("viewers")
        viewersRef = ref
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, prevKey: String?) {
                val id = snapshot.key ?: return
                if (!running) return
                if (synchronized(lock) { conns.containsKey(id) }) return
                if (!snapshot.hasChild("present")) {
                    // dữ liệu rác của máy đã tắt đột ngột: xoá, không cấp kênh
                    snapshot.ref.removeValue()
                    return
                }
                val conn = ViewerConn(id)
                synchronized(lock) { conns[id] = conn }
                connect(conn)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val id = snapshot.key ?: return
                val conn = synchronized(lock) { conns.remove(id) } ?: return
                conn.removed = true
                conn.reconnect?.let { handler.removeCallbacks(it) }
                releaseChannel(conn)
                updateStatus()
            }

            override fun onChildChanged(snapshot: DataSnapshot, prevKey: String?) {
                val id = snapshot.key ?: return
                val conn = synchronized(lock) { conns[id] } ?: return
                // máy vừa quay lại mà kênh chưa nối: nối ngay, không chờ backoff
                if (snapshot.hasChild("present") && !conn.connected && conn.pcm == null) {
                    conn.reconnect?.let { handler.removeCallbacks(it) }
                    connect(conn)
                }
            }

            override fun onChildMoved(snapshot: DataSnapshot, prevKey: String?) {}

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "Mất quyền đọc rooms/$code/viewers: ${error.code} ${error.message}")
            }
        }
        viewersListener = listener
        ref.addChildEventListener(listener)
    }

    /** Dừng kết nối và giải phóng toàn bộ tài nguyên. Gọi khi rời trang Điều khiển. */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        val ref = viewersRef
        val listener = viewersListener
        viewersRef = null
        viewersListener = null
        if (ref != null && listener != null) ref.removeEventListener(listener)

        val all = synchronized(lock) {
            val list = conns.values.toList()
            conns.clear()
            list
        }
        for (c in all) {
            c.removed = true
            c.reconnect?.let { handler.removeCallbacks(it) }
            releaseChannel(c)
        }
        handler.removeCallbacksAndMessages(null)
        statusListener?.onConnectionStatusChanged(0)
        factory?.dispose()
        factory = null
        eglBase?.release()
        eglBase = null
    }

    /** Đổi mã ghép nối: dừng rồi bắt đầu lại với mã mới. */
    fun restart(newCode: String) {
        stop()
        start(newCode)
    }

    /** Gửi một lệnh JSON tới mọi máy đang kết nối. Trả về số máy nhận được. */
    fun sendRemoteCommand(json: String): Int {
        val list = synchronized(lock) { conns.values.toList() }
        var ok = 0
        for (c in list) {
            if (!c.connected) continue
            val p = c.pcm ?: continue
            if (p.sendJsonMessage(json)) ok++
        }
        return ok
    }

    private fun connect(conn: ViewerConn) {
        val fac = factory ?: return
        if (!running || conn.removed) return
        releaseChannel(conn)

        var sigRef: SignalingClient? = null
        val sig = SignalingClient(
            roomCode = code,
            viewerId = conn.viewerId,
            isHost = true,
            listener = object : SignalingClient.Listener {
                override fun onOfferReceived(sdp: String) {}

                override fun onAnswerReceived(sdp: String) {
                    if (conn.sig === sigRef) conn.pcm?.handleAnswer(sdp)
                }

                override fun onIceCandidateReceived(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
                    if (conn.sig === sigRef) conn.pcm?.addIceCandidate(sdpMid, sdpMLineIndex, candidate)
                }

                override fun onRemoteDisconnected() {
                    handler.post { if (conn.sig === sigRef) scheduleReconnect(conn) }
                }
            }
        )
        sigRef = sig
        conn.sig = sig

        var pcmRef: PeerConnectionManager? = null
        val pcm = PeerConnectionManager(
            factory = fac,
            isHost = true,
            signalingClient = sig,
            remoteSink = null,
            onConnected = {
                if (conn.pcm === pcmRef) {
                    conn.connected = true
                    conn.attempt = 0
                    updateStatus()
                }
            },
            onDisconnected = {
                handler.post { if (conn.pcm === pcmRef) scheduleReconnect(conn) }
            }
        )
        pcmRef = pcm
        conn.pcm = pcm
        conn.connected = false
        pcm.init()
        pcm.startFileTransferOffer()
    }

    /** Đóng kênh WebRTC của một máy (không xoá máy khỏi danh sách). */
    private fun releaseChannel(conn: ViewerConn) {
        val p = conn.pcm
        val s = conn.sig
        conn.pcm = null
        conn.sig = null
        conn.connected = false
        try { p?.release() } catch (e: Exception) { Log.e(TAG, "release pcm lỗi", e) }
        try { s?.release() } catch (e: Exception) { Log.e(TAG, "release signaling lỗi", e) }
    }

    /** Nối lại một máy sau khi mất kết nối, chờ tăng dần (tối đa 20 giây). */
    private fun scheduleReconnect(conn: ViewerConn) {
        if (!running || conn.removed) return
        releaseChannel(conn)
        conn.attempt++
        val delay = minOf(1500L shl minOf(conn.attempt, 4), 20_000L)
        conn.reconnect?.let { handler.removeCallbacks(it) }
        val r = Runnable { if (running && !conn.removed) connect(conn) }
        conn.reconnect = r
        handler.postDelayed(r, delay)
        updateStatus()
    }

    private fun updateStatus() {
        val n = synchronized(lock) { conns.values.count { it.connected } }
        handler.post { statusListener?.onConnectionStatusChanged(n) }
    }
}
