package com.opencams.nearby.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Best-effort PTZ control for cameras whose operator PUBLICLY exposes a simple
 * CGI-style control endpoint (common pattern: <base>?move=left|right|up|down|stop).
 * Only used when a camera entry provides ptzBaseUrl; most public feeds are
 * view-only and never reach this code.
 */
class PtzController(private val baseUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    enum class Move { LEFT, RIGHT, UP, DOWN, ZOOM_IN, ZOOM_OUT, STOP }

    suspend fun send(move: Move): Boolean = withContext(Dispatchers.IO) {
        val cmd = when (move) {
            Move.LEFT -> "left"
            Move.RIGHT -> "right"
            Move.UP -> "up"
            Move.DOWN -> "down"
            Move.ZOOM_IN -> "zoomin"
            Move.ZOOM_OUT -> "zoomout"
            Move.STOP -> "stop"
        }
        val sep = if (baseUrl.contains("?")) "&" else "?"
        val url = "$baseUrl${sep}move=$cmd"
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
