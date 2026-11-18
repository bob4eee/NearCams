package com.camradar.core.discovery

import com.camradar.core.geo.UserFix
import com.camradar.core.model.AuthAssessment
import com.camradar.core.model.AuthClassification
import com.camradar.core.model.AuthScope
import com.camradar.core.model.Camera
import com.camradar.core.model.CameraCategory
import com.camradar.core.model.DiscoverySource
import com.camradar.core.model.LocationConfidence
import com.camradar.core.model.Protocol
import com.camradar.core.net.Ipv4
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

data class DiscoveryConfig(
    val multicastListenMs: Long = 3_000,
    val connectTimeoutMs: Int = 400,
    val probeTimeoutMs: Int = 2_500,
    val concurrency: Int = 32,
    val rtspPorts: List<Int> = listOf(554, 8554),
    val httpPorts: List<Int> = listOf(80, 8080, 8000),
    val httpsPorts: List<Int> = listOf(443),
    val portSweep: Boolean = true,
)

sealed interface DiscoveryEvent {
    data class Phase(val name: String) : DiscoveryEvent
    data class Progress(val scanned: Int, val total: Int) : DiscoveryEvent
    data class Found(val camera: Camera, val evidence: List<AuthEvidence>, val endpoints: Endpoints) : DiscoveryEvent
    data class Denied(val reason: String) : DiscoveryEvent
    data class Finished(val cameraCount: Int, val hostsResponding: Int) : DiscoveryEvent
}

/** Where to reach a device's services; kept so the user can later verify their own credentials. */
data class Endpoints(val ip: String, val rtspPort: Int?, val httpPort: Int?, val onvifXAddr: String?)

/**
 * Active discovery on ONE user-authorized private network.
 * Checks performed: ONVIF WS-Discovery, SSDP M-SEARCH, TCP connect on camera ports, RTSP OPTIONS/DESCRIBE
 * (no SETUP/PLAY), HTTP GET /, ONVIF GetDeviceInformation. No credentials are ever guessed.
 */
class LocalDiscoveryEngine(
    private val transport: NetTransport,
    private val config: DiscoveryConfig = DiscoveryConfig(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Candidate(val ip: String) {
        var onvif: OnvifProbeMatch? = null
        var ssdp: SsdpResponse? = null
        var upnp: UpnpDescription? = null
        val openPorts = sortedSetOf<Int>()
    }

    fun discover(decision: GuardDecision, fix: UserFix? = null): Flow<DiscoveryEvent> = channelFlow {
        if (decision !is GuardDecision.Allowed) { send(DiscoveryEvent.Denied((decision as GuardDecision.Denied).reason)); return@channelFlow }
        val allowed = { ip: String -> NetworkGuard.isTargetAllowed(decision, ip) }
        val candidates = linkedMapOf<String, Candidate>()
        fun cand(ip: String) = candidates.getOrPut(ip) { Candidate(ip) }

        send(DiscoveryEvent.Phase("ONVIF WS-Discovery"))
        runCatching { transport.multicast(Onvif.MULTICAST_GROUP, Onvif.PORT, Onvif.probeMessage(), config.multicastListenMs) }.getOrDefault(emptyList())
            .forEach { d ->
                Onvif.parseProbeMatches(d.text).forEach { m ->
                    val ip = m.xAddrs.firstNotNullOfOrNull { runCatching { URI(it).host }.getOrNull() } ?: d.host
                    if (allowed(ip)) cand(ip).onvif = m
                }
            }

        send(DiscoveryEvent.Phase("UPnP / SSDP"))
        runCatching { transport.multicast(Ssdp.MULTICAST_GROUP, Ssdp.PORT, Ssdp.mSearch(), config.multicastListenMs) }.getOrDefault(emptyList())
            .forEach { d -> if (allowed(d.host)) Ssdp.parseResponse(d.text)?.let { cand(d.host).ssdp = it } }
        candidates.values.filter { it.ssdp?.location != null }.forEach { c ->
            ensureActive()
            val loc = c.ssdp!!.location!!
            val host = runCatching { URI(loc).host }.getOrNull()
            if (host != null && allowed(host)) transport.http("GET", loc, config.probeTimeoutMs)?.takeIf { it.status == 200 }
                ?.let { c.upnp = Ssdp.parseDescription(it.body) }
        }

        val ports = config.rtspPorts + config.httpPorts + config.httpsPorts
        if (config.portSweep) {
            send(DiscoveryEvent.Phase("Service probe on ${decision.subnet}"))
            val hosts = decision.subnet.hosts().map(Ipv4::format).filter(allowed).toList()
            val done = AtomicInteger()
            val sem = Semaphore(config.concurrency)
            coroutineScope {
                hosts.map { ip ->
                    async {
                        sem.withPermit {
                            val open = ports.filter { p -> transport.isPortOpen(ip, p, config.connectTimeoutMs) }
                            val n = done.incrementAndGet()
                            if (n % 16 == 0 || n == hosts.size) send(DiscoveryEvent.Progress(n, hosts.size))
                            ip to open
                        }
                    }
                }.awaitAll()
            }.forEach { (ip, open) -> if (open.isNotEmpty()) cand(ip).openPorts += open }
        } else {
            candidates.values.forEach { c -> c.openPorts += ports.filter { transport.isPortOpen(c.ip, it, config.connectTimeoutMs) } }
        }

        send(DiscoveryEvent.Phase("Identifying devices"))
        var count = 0
        val sem = Semaphore(8)
        coroutineScope {
            candidates.values.map { c -> async { sem.withPermit { identify(c, decision, fix) } } }.awaitAll()
        }.filterNotNull().forEach { send(it); count++ }
        send(DiscoveryEvent.Finished(count, candidates.size))
    }

    private suspend fun identify(c: Candidate, decision: GuardDecision.Allowed, fix: UserFix?): DiscoveryEvent.Found? {
        val evidence = mutableListOf<AuthEvidence>()
        val protocols = mutableSetOf<Protocol>()
        val rtspPort = c.openPorts.firstOrNull { it in config.rtspPorts }
        val httpPort = c.openPorts.firstOrNull { it in config.httpPorts }
        if (c.openPorts.any { it in config.httpsPorts }) protocols += Protocol.HTTPS
        if (c.ssdp != null) protocols += Protocol.UPNP

        var rtspServer: String? = null
        if (rtspPort != null) {
            val url = "rtsp://${c.ip}:$rtspPort/"
            val opt = transport.exchange(c.ip, rtspPort, Rtsp.options(url), config.probeTimeoutMs)?.let(TextResponse::parse)
            if (opt != null && opt.protocol.startsWith("RTSP")) {
                protocols += Protocol.RTSP
                rtspServer = opt.header("server")
                val desc = transport.exchange(c.ip, rtspPort, Rtsp.describe(url), config.probeTimeoutMs)?.let(TextResponse::parse)
                if (desc != null) evidence += AuthEvidence.RtspDescribe(desc.status, desc.status == 200 && desc.body.contains("v=0"))
            }
        }

        var httpServer: String? = null
        var title: String? = null
        if (httpPort != null) {
            transport.http("GET", "http://${c.ip}:$httpPort/", config.probeTimeoutMs)?.let { r ->
                protocols += Protocol.HTTP
                httpServer = r.header("server")
                title = Fingerprinter.htmlTitle(r.body)
                evidence += AuthEvidence.HttpRoot(r.status, r.headersNamed("www-authenticate").isNotEmpty())
            }
        }

        val xAddr = c.onvif?.xAddrs?.firstOrNull { x -> runCatching { URI(x).host }.getOrNull()?.let { NetworkGuard.isTargetAllowed(decision, it) } == true }
            ?: httpPort?.let { "http://${c.ip}:$it/onvif/device_service" }
        var info: OnvifDeviceInfo? = null
        if (xAddr != null) {
            transport.http("POST", xAddr, config.probeTimeoutMs, Onvif.getDeviceInformationRequest(), "application/soap+xml; charset=utf-8")?.let { r ->
                val res = Onvif.parseDeviceInformation(r.status, r.body)
                if (res !is OnvifInfoResult.Failed || c.onvif != null) {
                    protocols += Protocol.ONVIF
                    evidence += AuthEvidence.OnvifInfo(res)
                }
                if (res is OnvifInfoResult.Info) info = res.info
            }
        }
        if (c.onvif != null) protocols += Protocol.ONVIF

        val fp = Fingerprinter.identify(rtspServer, httpServer, title, c.ssdp?.server, c.upnp?.manufacturer, c.upnp?.modelName,
            c.upnp?.deviceType, c.onvif?.name, c.onvif?.hardware, info?.manufacturer)
        val isCamera = c.onvif?.isVideoDevice == true || c.onvif != null || info != null || Protocol.RTSP in protocols ||
            (fp.looksLikeCamera && (c.upnp != null || Protocol.HTTP in protocols))
        if (!isCamera) return null

        val manufacturer = info?.manufacturer ?: c.upnp?.manufacturer ?: fp.manufacturer
        val model = info?.model ?: c.upnp?.modelName ?: c.onvif?.hardware
        val source = when { c.onvif != null -> DiscoverySource.LOCAL_ONVIF; c.ssdp != null -> DiscoverySource.LOCAL_SSDP; else -> DiscoverySource.LOCAL_PORT_PROBE }
        val camera = Camera(
            id = "lan:${decision.network.id}:${c.ip}",
            name = c.onvif?.name ?: c.upnp?.friendlyName ?: title ?: listOfNotNull(manufacturer, model).joinToString(" ").ifBlank { "IP camera ${c.ip}" },
            manufacturer = manufacturer, model = model, ipAddress = c.ip,
            location = fix?.position,
            locationConfidence = if (fix != null) LocationConfidence.USER_LOCATION_AT_DISCOVERY else LocationConfidence.UNKNOWN,
            category = CameraCategory.LOCAL_NETWORK, protocols = protocols,
            auth = AuthClassifier.classify(evidence), source = source,
            sourceDetail = "Network ${decision.network.label} (${decision.subnet}); open ports ${c.openPorts.joinToString()}",
            networkId = decision.network.id, lastSeenEpochMs = clock(),
        )
        return DiscoveryEvent.Found(camera, evidence, Endpoints(c.ip, rtspPort, httpPort, xAddr))
    }

    /**
     * Checks ONE set of credentials the user typed in for a device on an authorized network.
     * Exactly one authenticated attempt per protocol — never a list, never retried with variations.
     */
    suspend fun verifyCredentials(decision: GuardDecision, endpoints: Endpoints, credentials: Credentials, previous: List<AuthEvidence>): AuthAssessment {
        if (decision !is GuardDecision.Allowed || !NetworkGuard.isTargetAllowed(decision, endpoints.ip))
            return AuthAssessment(AuthClassification.UNKNOWN, AuthScope.NONE, "Device is not on the authorized network.")
        val checks = mutableListOf<AuthEvidence.UserCredentialCheck>()

        endpoints.rtspPort?.let { port ->
            val url = "rtsp://${endpoints.ip}:$port/"
            val first = transport.exchange(endpoints.ip, port, Rtsp.describe(url, 1), config.probeTimeoutMs)?.let(TextResponse::parse)
            if (first?.status == 401) {
                val (digest, basic) = HttpAuth.parseChallenges(first.headersNamed("www-authenticate"))
                val authz = digest?.let { HttpAuth.digestAuthorization(credentials, it, "DESCRIBE", url) } ?: basic?.let { HttpAuth.basicAuthorization(credentials) }
                if (authz != null) {
                    val second = transport.exchange(endpoints.ip, port, Rtsp.describe(url, 2, authz), config.probeTimeoutMs)?.let(TextResponse::parse)
                    // 404 after auth means credentials accepted but stream path unknown — still an accepted login.
                    if (second != null) checks += AuthEvidence.UserCredentialCheck(AuthScope.VIDEO_STREAM, second.status != 401 && second.status != 403)
                }
            }
        }
        endpoints.onvifXAddr?.takeIf { x -> runCatching { URI(x).host }.getOrNull()?.let { NetworkGuard.isTargetAllowed(decision, it) } == true }?.let { x ->
            transport.http("POST", x, config.probeTimeoutMs, Onvif.getDeviceInformationRequest(credentials), "application/soap+xml; charset=utf-8")?.let { r ->
                when (Onvif.parseDeviceInformation(r.status, r.body)) {
                    is OnvifInfoResult.Info -> checks += AuthEvidence.UserCredentialCheck(AuthScope.DEVICE_SERVICE, true)
                    OnvifInfoResult.NotAuthorized -> checks += AuthEvidence.UserCredentialCheck(AuthScope.DEVICE_SERVICE, false)
                    else -> Unit
                }
            }
        }
        return AuthClassifier.classify(previous.filterNot { it is AuthEvidence.UserCredentialCheck } + checks)
    }
}
