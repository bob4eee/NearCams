package com.camradar.core

import com.camradar.core.discovery.SocketTransport
import com.camradar.core.discovery.http
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

/** Uses only 127.0.0.1 sockets; no external network. */
class NetworkTimeoutTest {
    private val t = SocketTransport()

    @Test fun silentServerTimesOut() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) { runCatching { server.accept().also { Thread.sleep(5_000) } } }
            val start = System.currentTimeMillis()
            assertNull(t.exchange("127.0.0.1", server.localPort, "OPTIONS * RTSP/1.0\r\n\r\n", 300))
            assertTrue(System.currentTimeMillis() - start < 2_000)
        }
    }

    @Test fun closedPortIsNotOpen() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(t.isPortOpen("127.0.0.1", port, 200))
        assertNull(t.exchange("127.0.0.1", port, "x", 200))
    }

    @Test fun rtspResponseCompletesWithoutWaitingForClose() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(1024))
                    s.getOutputStream().write("RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\n\r\n".toByteArray()); s.getOutputStream().flush()
                    Thread.sleep(3_000) // keep-alive like a real RTSP server
                }
            }
            val start = System.currentTimeMillis()
            val r = t.exchange("127.0.0.1", server.localPort, "DESCRIBE rtsp://x/ RTSP/1.0\r\nCSeq: 1\r\n\r\n", 2_500)
            assertTrue(r!!.startsWith("RTSP/1.0 401"))
            assertTrue(System.currentTimeMillis() - start < 1_500)
        }
    }

    @Test fun httpOverLoopback() = runBlocking {
        ServerSocket(0).use { server ->
            thread(isDaemon = true) {
                server.accept().use { s ->
                    s.getInputStream().read(ByteArray(2048))
                    s.getOutputStream().write("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"cam\"\r\nContent-Length: 0\r\n\r\n".toByteArray())
                }
            }
            val r = t.http("GET", "http://127.0.0.1:${server.localPort}/", 1_000)!!
            assertEquals(401, r.status)
        }
    }

    @Test fun completenessRules() {
        assertTrue(t.isComplete("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok"))
        assertFalse(t.isComplete("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nok"))
        assertTrue(t.isComplete("RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"))
        assertFalse(t.isComplete("HTTP/1.1 200 OK\r\n\r\n<html>")) // HTTP without length waits for close
    }
}
