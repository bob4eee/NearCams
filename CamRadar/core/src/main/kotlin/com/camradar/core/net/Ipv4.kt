package com.camradar.core.net

/** IPv4 helpers. Local discovery is restricted to private (RFC 1918) ranges only. */
data class Ipv4Subnet(val network: Int, val prefix: Int) {
    init { require(prefix in 0..32) }

    val mask: Int get() = if (prefix == 0) 0 else (-1 shl (32 - prefix))
    val hostCount: Long get() = if (prefix >= 31) (1L shl (32 - prefix)) else (1L shl (32 - prefix)) - 2

    operator fun contains(ip: Int) = (ip and mask) == (network and mask)

    /** Usable host addresses, excluding network and broadcast. */
    fun hosts(): Sequence<Int> {
        val base = network and mask
        val size = 1L shl (32 - prefix)
        if (prefix >= 31) return (0 until size).asSequence().map { base + it.toInt() }
        return (1 until size - 1).asSequence().map { base + it.toInt() }
    }

    override fun toString() = "${Ipv4.format(network and mask)}/$prefix"

    companion object {
        fun parse(cidr: String): Ipv4Subnet {
            val (ip, p) = cidr.split('/').let { it[0] to (it.getOrNull(1)?.toInt() ?: 32) }
            return Ipv4Subnet(Ipv4.parse(ip), p).let { it.copy(network = it.network and it.mask) }
        }
    }
}

object Ipv4 {
    fun parse(s: String): Int {
        val parts = s.trim().split('.')
        require(parts.size == 4) { "Not an IPv4 address: $s" }
        return parts.fold(0) { acc, p ->
            val v = p.toInt(); require(v in 0..255) { "Bad octet in $s" }; (acc shl 8) or v
        }
    }

    fun format(ip: Int) = "${ip ushr 24 and 255}.${ip ushr 16 and 255}.${ip ushr 8 and 255}.${ip and 255}"

    private val PRIVATE = listOf(Ipv4Subnet.parse("10.0.0.0/8"), Ipv4Subnet.parse("172.16.0.0/12"), Ipv4Subnet.parse("192.168.0.0/16"))

    fun isPrivate(ip: Int) = PRIVATE.any { ip in it }
    fun isPrivate(ip: String) = runCatching { isPrivate(parse(ip)) }.getOrDefault(false)
    fun isPrivate(subnet: Ipv4Subnet) = PRIVATE.any { it.prefix <= subnet.prefix && subnet.network in it }
}
