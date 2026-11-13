package com.dk.home

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Nhận dạng giọng nói tiếng Việt bằng SpeechRecognizer của Android (dịch vụ Google trên máy). Có mạng: dùng nhận dạng trực tuyến như thường;
 * không có mạng (hoặc lỗi mạng giữa chừng): tự chuyển sang gói giọng nói tiếng Việt ngoại tuyến đã tải về máy.
 * Mọi hàm phải gọi trên luồng chính; các callback cũng chạy trên luồng chính.
 */
const val SILENCE_MS = 2000L
/** Sau khi bấm AI (bộ nhận dạng sẵn sàng), đợi người dùng bắt đầu nói tối đa chừng này; quá thì dừng nghe. */
const val START_WAIT_MS = 3000L
/** Mã lỗi riêng: không có mạng và máy chưa tải gói giọng nói tiếng Việt ngoại tuyến. */
const val ERR_NO_OFFLINE = 9001
/** Lỗi "mất kết nối với dịch vụ nhận dạng" (ERROR_SERVER_DISCONNECTED, hay gặp khi bấm lại quá nhanh). */
const val ERR_SERVER_DISCONNECTED = 11
/** Sau khi hủy một lần nghe, đợi chừng này rồi mới tạo bộ nhận dạng mới, để dịch vụ kịp nhả lượt cũ (tránh xung đột). */
const val RESTART_GAP_MS = 600L

class VoiceInput(private val ctx: Context) {
    private var rec: SpeechRecognizer? = null
    private var lastCancelAt = 0L                 // lúc hủy bộ nhận dạng gần nhất
    private var pendingStart: Runnable? = null    // lượt nghe đang chờ đến lúc bắt đầu
    private var retries = 0                       // số lần tự thử lại trong lượt hiện tại
    private val handler = Handler(Looper.getMainLooper())
    private val silenceStop = Runnable { stop() }          // im lặng đủ lâu -> chốt luôn, không chờ bộ nhận dạng
    private fun armSilence() { handler.removeCallbacks(silenceStop); handler.postDelayed(silenceStop, SILENCE_MS) }
    private fun disarmSilence() { handler.removeCallbacks(silenceStop); handler.removeCallbacks(startTimeout) }
    private var curFail: ((Int) -> Unit)? = null
    private val startTimeout = Runnable {            // 3 giây từ lúc sẵn sàng mà chưa ai nói -> dừng, báo "Không nghe thấy gì"
        val f = curFail
        cancel()
        f?.invoke(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)
    }

    fun available(): Boolean = try { SpeechRecognizer.isRecognitionAvailable(ctx) } catch (_: Throwable) { false }

    /** Có mạng internet đang dùng được không (không xác định được thì coi như có, để giữ hành vi cũ). */
    private fun online(): Boolean = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Throwable) { true }

    fun start(
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        onResult: (List<String>) -> Unit,
        onError: (Int) -> Unit
    ) {
        cancel()
        retries = 0
        val wait = (RESTART_GAP_MS - (SystemClock.uptimeMillis() - lastCancelAt)).coerceAtLeast(0L)
        val job = Runnable {
            pendingStart = null
            startRec(!online(), onPartial, onLevel, onResult, onError)
        }
        pendingStart = job
        handler.postDelayed(job, wait)
    }

    private fun startRec(
        offline: Boolean,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        onResult: (List<String>) -> Unit,
        onError: (Int) -> Unit
    ) {
        val r = try { SpeechRecognizer.createSpeechRecognizer(ctx) } catch (_: Throwable) { null }
        if (r == null) { onError(SpeechRecognizer.ERROR_CLIENT); return }
        rec = r
        var lastPartial = ""
        val fail = onError   // đặt tên khác để không lẫn với RecognitionListener.onError bên dưới
        curFail = fail
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { handler.removeCallbacks(startTimeout); handler.postDelayed(startTimeout, START_WAIT_MS) }
            override fun onBeginningOfSpeech() { handler.removeCallbacks(startTimeout); armSilence() }
            override fun onRmsChanged(rmsdB: Float) { onLevel(rmsdB) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                disarmSilence()
                // Một số máy báo lỗi (vd mã 5) ngay sau khi đã nghe được câu nói (do dừng nghe lúc im lặng): dùng luôn câu đã nghe
                if (lastPartial.isNotBlank() && (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_NO_MATCH ||
                        error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_AUDIO)) {
                    val t = lastPartial; lastPartial = ""
                    onResult(listOf(t)); return
                }
                val netErr = error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
                    error == SpeechRecognizer.ERROR_SERVER
                val busyErr = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == ERR_SERVER_DISCONNECTED
                if (busyErr && retries < 1) {
                    // Dịch vụ đang bận / vừa ngắt (thường do bấm lại quá nhanh hoặc app khác đang dùng micro): đợi rồi thử lại một lần
                    retries++
                    handler.postDelayed({ cancel(); startRec(offline, onPartial, onLevel, onResult, fail) }, RESTART_GAP_MS * 2)
                } else if (!offline && netErr) {
                    // Mạng chập chờn / máy chủ lỗi -> thử lại một lần bằng gói ngoại tuyến
                    handler.postDelayed({ cancel(); startRec(true, onPartial, onLevel, onResult, fail) }, RESTART_GAP_MS)
                } else if (offline && (netErr || error == 12 || error == 13)) {
                    fail(ERR_NO_OFFLINE)    // chưa có gói tiếng Việt ngoại tuyến trên máy
                } else fail(error)
            }
            override fun onResults(results: Bundle?) {
                disarmSilence()
                val l = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                onResult(l)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                    lastPartial = it
                    onPartial(it)
                    handler.removeCallbacks(startTimeout)
                    armSilence()       // còn đang nói (chữ vẫn thay đổi) -> đếm lại 2 giây
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Gợi ý cho bộ nhận dạng: im lặng 2 giây là xong (một số máy bỏ qua gợi ý này, đã có bộ đếm riêng ở trên)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            if (offline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        try { r.startListening(i) } catch (_: Throwable) { onError(SpeechRecognizer.ERROR_CLIENT) }
    }

    /** Nói xong sớm: bấm OK khi đang nghe -> nhận dạng luôn phần đã nói. */
    fun stop() { try { rec?.stopListening() } catch (_: Throwable) {} }

    /** Hủy hẳn, không trả kết quả. */
    fun cancel() {
        disarmSilence()
        pendingStart?.let { handler.removeCallbacks(it) }
        pendingStart = null
        val r = rec ?: return
        rec = null
        lastCancelAt = SystemClock.uptimeMillis()
        try { r.setRecognitionListener(null) } catch (_: Throwable) {}
        try { r.cancel() } catch (_: Throwable) {}
        try { r.destroy() } catch (_: Throwable) {}
    }
}

fun aiErrorText(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Không nghe thấy gì.\nBấm Nói để thử lại."
    ERR_NO_OFFLINE -> "Không có mạng và máy chưa có gói giọng nói tiếng Việt ngoại tuyến.\nTải ở: Cài đặt > Google > Nhận dạng giọng nói."
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Cần có mạng để nhận dạng giọng nói."
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Chưa cấp quyền Micro cho app."
    SpeechRecognizer.ERROR_AUDIO -> "Micro đang bận hoặc lỗi âm thanh."
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Bộ nhận dạng đang bận, thử lại sau giây lát."
    SpeechRecognizer.ERROR_SERVER -> "Máy chủ nhận dạng giọng nói lỗi, thử lại."
    ERR_SERVER_DISCONNECTED -> "Mất kết nối với dịch vụ giọng nói. Bấm AI thử lại sau vài giây."
    else -> "Không nhận dạng được (mã $code).\nKiểm tra mạng và app Google."
}

/** Bật / tắt đèn pin (flash). Trả về false nếu máy không có hoặc camera đang bận. */
object Torch {
    fun set(ctx: Context, on: Boolean): Boolean = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
        if (id == null) false else { cm.setTorchMode(id, on); true }
    } catch (_: Throwable) { false }
}

