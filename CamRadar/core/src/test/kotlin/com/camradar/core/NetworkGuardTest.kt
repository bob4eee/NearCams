package com.camradar.core

import com.camradar.core.discovery.AuthorizedNetwork
import com.camradar.core.discovery.CurrentNetwork
import com.camradar.core.discovery.CurrentNetwork.Transport
import com.camradar.core.discovery.GuardDecision
import com.camradar.core.discovery.NetworkGuard
import com.camradar.core.net.Ipv4
import com.camradar.core.net.Ipv4Subnet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkGuardTest {
    private val home = CurrentNetwork(Transport.WIFI, "HomeWiFi", "aa:bb:cc:dd:ee:ff", "192.168.1.23", 24)
    private val auth = AuthorizedNetwork("HomeWiFi|192.168.1.0/24", "Home", "HomeWiFi", "AA:BB:CC:DD:EE:FF", "192.168.1.0/24", 0)

    @Test fun subnetMath() {
        val s = Ipv4Subnet.parse("192.168.1.77/24")
        assertEquals("192.168.1.0/24", s.toString()); assertEquals(254, s.hosts().count())
        assertTrue(Ipv4.parse("192.168.1.200") in s); assertFalse(Ipv4.parse("192.168.2.1") in s)
    }

    @Test fun privateRanges() {
        assertTrue(Ipv4.isPrivate("10.1.2.3")); assertTrue(Ipv4.isPrivate("172.20.0.1")); assertTrue(Ipv4.isPrivate("192.168.0.9"))
        assertFalse(Ipv4.isPrivate("8.8.8.8")); assertFalse(Ipv4.isPrivate("172.32.0.1")); assertFalse(Ipv4.isPrivate("100.64.1.1"))
    }

    @Test fun allowsAuthorizedHomeNetwork() = assertTrue(NetworkGuard.evaluate(home, listOf(auth)) is GuardDecision.Allowed)

    @Test fun deniesUnauthorizedNetwork() = assertTrue(NetworkGuard.evaluate(home.copy(ssid = "CoffeeShop"), listOf(auth)) is GuardDecision.Denied)

    @Test fun deniesWithNoAuthorizations() = assertTrue(NetworkGuard.evaluate(home, emptyList()) is GuardDecision.Denied)

    @Test fun deniesDifferentAccessPointSameSsid() =
        assertTrue(NetworkGuard.evaluate(home.copy(bssid = "11:22:33:44:55:66"), listOf(auth)) is GuardDecision.Denied)

    @Test fun deniesCellular() = assertTrue(NetworkGuard.evaluate(home.copy(transport = Transport.CELLULAR), listOf(auth)) is GuardDecision.Denied)

    @Test fun deniesPublicSubnet() {
        val pub = home.copy(localAddress = "81.161.1.5")
        val d = NetworkGuard.evaluate(pub, listOf(auth.copy(subnetCidr = "81.161.1.0/24")))
        assertTrue((d as GuardDecision.Denied).reason.contains("not a private"))
    }

    @Test fun deniesHugeSubnet() {
        val big = home.copy(localAddress = "10.0.0.5", prefixLength = 16)
        assertTrue(NetworkGuard.evaluate(big, listOf(auth.copy(subnetCidr = "10.0.0.0/16"))) is GuardDecision.Denied)
    }

    @Test fun targetsOutsideSubnetRejected() {
        val ok = NetworkGuard.evaluate(home, listOf(auth)) as GuardDecision.Allowed
        assertTrue(NetworkGuard.isTargetAllowed(ok, "192.168.1.64"))
        assertFalse(NetworkGuard.isTargetAllowed(ok, "192.168.2.64"))
        assertFalse(NetworkGuard.isTargetAllowed(ok, "8.8.8.8"))
        assertFalse(NetworkGuard.isTargetAllowed(ok, "not-an-ip"))
    }

    @Test fun nullNetworkDenied() = assertTrue(NetworkGuard.evaluate(null, listOf(auth)) is GuardDecision.Denied)
}
