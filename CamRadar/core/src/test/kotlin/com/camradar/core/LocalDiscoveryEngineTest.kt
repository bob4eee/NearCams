package com.camradar.core

import com.camradar.core.discovery.AuthorizedNetwork
import com.camradar.core.discovery.Credentials
import com.camradar.core.discovery.CurrentNetwork
import com.camradar.core.discovery.Datagram
import com.camradar.core.discovery.DiscoveryConfig
import com.camradar.core.discovery.DiscoveryEvent
import com.camradar.core.discovery.LocalDiscoveryEngine
import com.camradar.core.discovery.NetworkGuard
import com.camradar.core.geo.LatLon
import com.camradar.core.geo.UserFix
import com.camradar.core.model.AuthClassification
import com.camradar.core.model.DiscoverySource
import com.camradar.core.model.LocationConfidence
import com.camradar.core.model.Protocol
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalDiscoveryEngineTest {
    private val net = CurrentNetwork(CurrentNetwork.Transport.WIFI, "Lab", null, "192.168.50.10", 24)
    private val auth = AuthorizedNetwork("Lab|192.168.50.0/24", "Lab", "Lab", null, "192.168.50.0/24", 0)
    private val allowed = NetworkGuard.evaluate(net, listOf(auth))
    private val cfg = DiscoveryConfig(multicastListenMs = 10, connectTimeoutMs = 10, probeTimeoutMs = 50)

    private fun fakeLab() = FakeTransport().apply {
        // ONVIF camera at .64 that requires auth on RTSP
        multicastReplies[3702] = listOf(Datagram("192.168.50.64", """<d:ProbeMatches><d:ProbeMatch><d:Types>dn:NetworkVideoTransmitter</d:Types>
            <d:Scopes>onvif://www.onvif.org/name/Garage</d:Scopes><d:XAddrs>http://192.168.50.64/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches>"""),
            // A spoofed reply pointing outside the network must be ignored
            Datagram("192.168.50.99", """<d:ProbeMatch><d:XAddrs>http://203.0.113.5/onvif/device_service</d:XAddrs></d:ProbeMatch>"""))
        openPorts["192.168.50.64"] = setOf(80, 554)
        handlers["192.168.50.64" to 554] = { req ->
            when {
                req.startsWith("OPTIONS") -> "RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: OPTIONS, DESCRIBE\r\nServer: Hikvision RTSP\r\n\r\n"
                req.contains("Authorization: Digest") && req.contains("username=\"owner\"") ->
                    "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Length: 5\r\n\r\nv=0\r\n"
                else -> "RTSP/1.0 401 Unauthorized\r\nCSeq: 2\r\nWWW-Authenticate: Digest realm=\"cam\", nonce=\"n1\"\r\n\r\n"
            }
        }
        handlers["192.168.50.64" to 80] = { req ->
            if (req.startsWith("POST")) "HTTP/1.1 400 Bad Request\r\n\r\n<env:Fault><env:Value>ter:NotAuthorized</env:Value></env:Fault>"
            else "HTTP/1.1 200 OK\r\nServer: App-webs/\r\n\r\n<title>Hikvision Login</title>"
        }
        // Open-SDP camera at .80 (no ONVIF, found only by service probe)
        openPorts["192.168.50.80"] = setOf(554)
        handlers["192.168.50.80" to 554] = { req ->
            if (req.startsWith("OPTIONS")) "RTSP/1.0 200 OK\r\nCSeq: 1\r\n\r\n"
            else "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: 9\r\n\r\nv=0\r\no=-\r\n"
        }
        // A router web UI at .1 — not a camera
        openPorts["192.168.50.1"] = setOf(80)
        handlers["192.168.50.1" to 80] = { "HTTP/1.1 200 OK\r\nServer: lighttpd\r\n\r\n<title>Router</title>" }
    }

    @Test fun discoversCamerasClassifiesAndSkipsNonCameras() = runBlocking {
        val t = fakeLab()
        val fix = UserFix(LatLon(42.7, 23.3), 12f, 0)
        val found = LocalDiscoveryEngine(t, cfg) { 42L }.discover(allowed, fix).filterIsInstance<DiscoveryEvent.Found>().toList()
            .associateBy { it.camera.ipAddress }
        assertEquals(setOf("192.168.50.64", "192.168.50.80"), found.keys)

        val garage = found.getValue("192.168.50.64").camera
        assertEquals("Garage", garage.name)
        assertEquals(DiscoverySource.LOCAL_ONVIF, garage.source)
        assertEquals("Hikvision", garage.manufacturer)
        assertTrue(garage.protocols.containsAll(setOf(Protocol.ONVIF, Protocol.RTSP, Protocol.HTTP)))
        assertEquals(AuthClassification.AUTH_REQUIRED, garage.auth.classification)
        assertEquals(LocationConfidence.USER_LOCATION_AT_DISCOVERY, garage.locationConfidence)
        assertEquals(42L, garage.lastSeenEpochMs)

        val open = found.getValue("192.168.50.80").camera
        assertEquals(DiscoverySource.LOCAL_PORT_PROBE, open.source)
        assertEquals(AuthClassification.NO_AUTH_DETECTED, open.auth.classification)
    }

    @Test fun neverContactsHostsOutsideAuthorizedSubnet() = runBlocking {
        val t = fakeLab()
        LocalDiscoveryEngine(t, cfg).discover(allowed).toList()
        assertTrue(t.contacted.isNotEmpty())
        assertTrue(t.contacted.all { it.startsWith("192.168.50.") })
        assertFalse(t.contacted.contains("203.0.113.5"))
    }

    @Test fun neverSendsSetupOrPlayOrCredentialsDuringDiscovery() = runBlocking {
        val t = fakeLab()
        LocalDiscoveryEngine(t, cfg).discover(allowed).toList()
        assertTrue(t.requests.none { it.startsWith("SETUP") || it.startsWith("PLAY") || it.contains("Authorization:") || it.contains("UsernameToken") })
    }

    @Test fun deniedDecisionSendsNothing() = runBlocking {
        val t = fakeLab()
        val events = LocalDiscoveryEngine(t, cfg).discover(NetworkGuard.evaluate(net, emptyList())).toList()
        assertTrue(events.single() is DiscoveryEvent.Denied)
        assertTrue(t.contacted.isEmpty())
    }

    @Test fun verifiesUserCredentialsWithSingleAttempt() = runBlocking {
        val t = fakeLab()
        val engine = LocalDiscoveryEngine(t, cfg)
        val f = engine.discover(allowed).filterIsInstance<DiscoveryEvent.Found>().toList().first { it.camera.ipAddress == "192.168.50.64" }
        t.requests.clear()
        val ok = engine.verifyCredentials(allowed, f.endpoints, Credentials("owner", "pw"), f.evidence)
        assertEquals(AuthClassification.VERIFIED_WITH_USER_CREDENTIALS, ok.classification)
        assertEquals(1, t.requests.count { it.contains("Authorization: Digest") })

        val bad = engine.verifyCredentials(allowed, f.endpoints, Credentials("someone", "x"), f.evidence)
        assertEquals(AuthClassification.AUTH_REQUIRED, bad.classification)
        assertTrue(bad.rationale.contains("rejected"))
    }

    @Test fun verifyRefusesOffNetworkTarget() = runBlocking {
        val t = FakeTransport()
        val res = LocalDiscoveryEngine(t, cfg).verifyCredentials(allowed,
            com.camradar.core.discovery.Endpoints("10.9.9.9", 554, null, null), Credentials("a", "b"), emptyList())
        assertEquals(AuthClassification.UNKNOWN, res.classification)
        assertTrue(t.contacted.isEmpty())
    }

    @Test fun progressReachesTotal() = runBlocking {
        val p = LocalDiscoveryEngine(fakeLab(), cfg).discover(allowed).filterIsInstance<DiscoveryEvent.Progress>().toList()
        assertEquals(254, p.last().scanned); assertEquals(254, p.last().total)
    }

    @Test fun stopCancelsDiscovery() = runBlocking {
        val slow = object : com.camradar.core.discovery.NetTransport by fakeLab() {
            override suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean { kotlinx.coroutines.delay(1_000); return false }
        }
        val start = System.currentTimeMillis()
        val job = launchCollect(LocalDiscoveryEngine(slow, cfg))
        kotlinx.coroutines.delay(100); job.cancel(); job.join()
        assertTrue(job.isCancelled)
        assertTrue("took too long to stop", System.currentTimeMillis() - start < 1_500)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCollect(e: LocalDiscoveryEngine) =
        launch(kotlinx.coroutines.Dispatchers.Default) { e.discover(allowed).collect {} }

}
