package com.dk.home.remote

import java.util.concurrent.CopyOnWriteArrayList

/** Chế độ giao diện TV đang hiển thị (TV gửi sang qua DataChannel): "tv" hoặc "yt". */
object RemoteState {
    @Volatile var mode: String = "tv"
        private set
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(l: (String) -> Unit) { listeners.add(l); l(mode) }
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    fun onMode(m: String) {
        if (m != "tv" && m != "yt") return
        mode = m
        listeners.forEach { it(m) }
    }
}
