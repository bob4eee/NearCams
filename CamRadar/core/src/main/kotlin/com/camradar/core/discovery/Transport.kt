package com.camradar.core.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI

data class Datagram(val host: String, val text: String)

/** All network I/O goes through this interface so the engine can be tested without real devices. */
interface NetTransport {
    suspend fun multicast(group: String, port: Int, payload: String, listenMs: Long): List<Datagram>
    suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean
    /** Sends a raw text request and returns the raw response, or null on connect failure / timeout with no data. */
    suspend fun exchange(host: String, port: Int, request: String, timeoutMs: Int): String?
}

/** Plain-HTTP helper over [NetTransport.exchange] (LAN device interfaces; no redirects followed). */
suspend fun NetTransport.http(method: String, url: String, timeoutMs: Int, body: String? = null, contentType: String? = null, extraHeaders: Map<String, String> = emptyMap()): TextResponse? {
    val u = runCatching { URI(url) }.getOrNull() ?: return null
    if (u.scheme != "http") return null
    val host = u.host ?: return null
    val port = if (u.port > 0) u.port else 80
    val path = (u.rawPath?.ifEmpty { "/" } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
    val bytes = body?.toByteArray()
    val req = buildString {
        append("$method $path HTTP/1.1\r\nHost: $host:$port\r\nUser-Agent: CamRadar/1.0\r\nConnection: close\r\n")
        contentType?.let { append("Content-Type: $it\r\n") }
        bytes?.let { append("Content-Length: ${it.size}\r\n") }
        extraHeaders.forEach { (k, v) -> append("$k: $v\r\n") }
        append("\r\n")
        body?.let { append(it) }
    }
    return exchange(host, port, req, timeoutMs)?.let { TextResponse.parse(it) }
}

class SocketTransport(private val maxResponseBytes: Int = 128 * 1024) : NetTransport {

    override suspend fun multicast(group: String, port: Int, payload: String, listenMs: Long): List<Datagram> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Datagram>()
        DatagramSocket().use { sock ->
            val data = payload.toByteArray()
            sock.send(DatagramPacket(data, data.size, InetAddress.getByName(group), port))
            val deadline = System.currentTimeMillis() + listenMs
            val buf = ByteArray(16 * 1024)
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) break
                sock.soTimeout = remaining.toInt().coerceAtLeast(1)
                val pkt = DatagramPacket(buf, buf.size)
                try { sock.receive(pkt) } catch (_: SocketTimeoutException) { break }
                out += Datagram(pkt.address.hostAddress, String(pkt.data, 0, pkt.length))
            }
        }
        out
    }

    override suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true } }.getOrDefault(false)
    }

    override suspend fun exchange(host: String, port: Int, request: String, timeoutMs: Int): String? = withContext(Dispatchers.IO) {
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), timeoutMs)
                s.soTimeout = timeoutMs
                s.getOutputStream().apply { write(request.toByteArray()); flush() }
                val input = s.getInputStream()
                val acc = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (acc.size() < maxResponseBytes && System.currentTimeMillis() < deadline) {
                    val n = try { input.read(buf) } catch (_: SocketTimeoutException) { break }
                    if (n < 0) break
                    acc.write(buf, 0, n)
                    if (isComplete(acc.toString(Charsets.ISO_8859_1.name()))) break
                }
                acc.takeIf { it.size() > 0 }?.toString(Charsets.UTF_8.name())
            }
        } catch (e: java.io.IOException) {
            null
        }
    }

    /** RTSP keeps connections open, so stop reading once headers + Content-Length bytes have arrived. */
    internal fun isComplete(raw: String): Boolean {
        val end = raw.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: return false
        val len = Regex("""(?im)^content-length:\s*(\d+)""").find(raw.substring(0, end))?.groupValues?.get(1)?.toIntOrNull()
            ?: return raw.startsWith("RTSP/")
        return raw.length - (end + 4) >= len
    }
}
