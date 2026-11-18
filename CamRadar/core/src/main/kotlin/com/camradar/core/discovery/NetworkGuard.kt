package com.camradar.core.discovery

import com.camradar.core.net.Ipv4
import com.camradar.core.net.Ipv4Subnet

/** The network the phone is currently attached to, as reported by the platform. */
data class CurrentNetwork(
    val transport: Transport,
    val ssid: String?,
    val bssid: String?,
    val localAddress: String,
    val prefixLength: Int,
) {
    enum class Transport { WIFI, ETHERNET, CELLULAR, VPN, OTHER }

    val subnet: Ipv4Subnet get() = Ipv4Subnet.parse("$localAddress/$prefixLength")
}

/** A network the user has explicitly confirmed they own or are permitted to scan. */
data class AuthorizedNetwork(
    val id: String,
    val label: String,
    val ssid: String?,
    val bssid: String?,
    val subnetCidr: String,
    val authorizedAtEpochMs: Long,
)

sealed interface GuardDecision {
    data class Allowed(val network: AuthorizedNetwork, val subnet: Ipv4Subnet) : GuardDecision
    data class Denied(val reason: String) : GuardDecision
}

/**
 * Gatekeeper for active discovery. Every rule here must pass before a single packet is sent:
 *  - the phone is on Wi-Fi or Ethernet (never cellular/VPN),
 *  - the subnet is private (RFC 1918) and no larger than /22,
 *  - the user has authorized exactly this network (SSID/BSSID + subnet),
 * and every probe target is re-checked against the authorized subnet.
 */
object NetworkGuard {
    const val MIN_PREFIX = 22

    fun authorizationIdFor(net: CurrentNetwork) = "${net.ssid ?: "wired"}|${net.subnet}"

    fun evaluate(current: CurrentNetwork?, authorized: List<AuthorizedNetwork>): GuardDecision {
        if (current == null) return GuardDecision.Denied("Not connected to a local network")
        if (current.transport != CurrentNetwork.Transport.WIFI && current.transport != CurrentNetwork.Transport.ETHERNET)
            return GuardDecision.Denied("Active discovery only runs on Wi-Fi or Ethernet, not ${current.transport}")
        val subnet = runCatching { current.subnet }.getOrNull()
            ?: return GuardDecision.Denied("Could not determine local subnet")
        if (!Ipv4.isPrivate(subnet)) return GuardDecision.Denied("Subnet $subnet is not a private network")
        if (subnet.prefix < MIN_PREFIX) return GuardDecision.Denied("Subnet $subnet is too large (limit /$MIN_PREFIX)")
        val match = authorized.firstOrNull { a ->
            a.subnetCidr == subnet.toString() &&
                (a.ssid == current.ssid) &&
                (a.bssid == null || current.bssid == null || a.bssid.equals(current.bssid, ignoreCase = true))
        } ?: return GuardDecision.Denied("This network (${current.ssid ?: "wired"}, $subnet) has not been authorized")
        return GuardDecision.Allowed(match, subnet)
    }

    fun isTargetAllowed(decision: GuardDecision.Allowed, ip: String): Boolean =
        runCatching { Ipv4.parse(ip) in decision.subnet && Ipv4.isPrivate(ip) }.getOrDefault(false)
}
