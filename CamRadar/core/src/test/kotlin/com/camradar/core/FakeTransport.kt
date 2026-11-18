package com.camradar.core

import com.camradar.core.discovery.Datagram
import com.camradar.core.discovery.NetTransport
import java.util.concurrent.CopyOnWriteArrayList

/** Scriptable in-memory network. Records every contacted host so tests can prove nothing left the subnet. */
class FakeTransport : NetTransport {
    val multicastReplies = mutableMapOf<Int, List<Datagram>>()
    val openPorts = mutableMapOf<String, Set<Int>>()
    /** (host, port) -> function of request text -> raw response. */
    val handlers = mutableMapOf<Pair<String, Int>, (String) -> String?>()
    val contacted = CopyOnWriteArrayList<String>()
    val requests = CopyOnWriteArrayList<String>()

    override suspend fun multicast(group: String, port: Int, payload: String, listenMs: Long) = multicastReplies[port].orEmpty()
    override suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        contacted += host; return port in openPorts[host].orEmpty()
    }
    override suspend fun exchange(host: String, port: Int, request: String, timeoutMs: Int): String? {
        contacted += host; requests += request
        return handlers[host to port]?.invoke(request)
    }
}
