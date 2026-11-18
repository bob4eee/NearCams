package com.camradar.core.discovery

import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

// ---------------------------------------------------------------- generic text-protocol response

/** Parsed RTSP/HTTP style response (status line + headers + body). */
data class TextResponse(val protocol: String, val status: Int, val reason: String, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]?.firstOrNull()
    fun headersNamed(name: String): List<String> = headers[name.lowercase()].orEmpty()

    companion object {
        /** Returns null for anything that is not a well-formed status line. */
        fun parse(raw: String): TextResponse? {
            val split = raw.indexOf("\r\n\r\n").let { if (it >= 0) it to 4 else raw.indexOf("\n\n") to 2 }
            val head = if (split.first >= 0) raw.substring(0, split.first) else raw
            val body = if (split.first >= 0) raw.substring(split.first + split.second) else ""
            val lines = head.split("\r\n", "\n")
            val m = Regex("""^(RTSP/\d\.\d|HTTP/\d(?:\.\d)?)\s+(\d{3})\s*(.*)$""").find(lines.first().trim()) ?: return null
            val headers = linkedMapOf<String, MutableList<String>>()
            lines.drop(1).forEach { l ->
                val c = l.indexOf(':')
                if (c > 0) headers.getOrPut(l.substring(0, c).trim().lowercase()) { mutableListOf() } += l.substring(c + 1).trim()
            }
            return TextResponse(m.groupValues[1], m.groupValues[2].toInt(), m.groupValues[3], headers, body)
        }
    }
}

// ---------------------------------------------------------------- ONVIF

data class OnvifProbeMatch(val endpoint: String?, val xAddrs: List<String>, val scopes: List<String>, val types: List<String>) {
    private fun scope(key: String) = scopes.firstOrNull { it.contains("onvif.org/$key/", ignoreCase = true) }
        ?.substringAfter("onvif.org/$key/")?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    val name get() = scope("name")
    val hardware get() = scope("hardware")
    val isVideoDevice get() = types.any { it.contains("NetworkVideoTransmitter", true) } ||
        scopes.any { it.contains("/type/video_encoder", true) || it.contains("/type/NetworkVideoTransmitter", true) }
}

data class OnvifDeviceInfo(val manufacturer: String?, val model: String?, val firmware: String?, val serial: String?, val hardwareId: String?)

sealed interface OnvifInfoResult {
    data class Info(val info: OnvifDeviceInfo) : OnvifInfoResult
    data object NotAuthorized : OnvifInfoResult
    data class Failed(val reason: String) : OnvifInfoResult
}

object Onvif {
    const val MULTICAST_GROUP = "239.255.255.250"
    const val PORT = 3702

    fun probeMessage(messageId: String = "uuid:" + UUID.randomUUID()): String = """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>$messageId</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""

    private fun tag(xml: String, local: String): List<String> =
        Regex("""<(?:[\w-]+:)?$local\b[^>]*>(.*?)</(?:[\w-]+:)?$local>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1].trim() }.toList()

    fun parseProbeMatches(xml: String): List<OnvifProbeMatch> =
        Regex("""<(?:[\w-]+:)?ProbeMatch\b[^>]*>(.*?)</(?:[\w-]+:)?ProbeMatch>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { m ->
            val block = m.groupValues[1]
            OnvifProbeMatch(
                endpoint = tag(block, "Address").firstOrNull(),
                xAddrs = tag(block, "XAddrs").flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() },
                scopes = tag(block, "Scopes").flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() },
                types = tag(block, "Types").flatMap { it.split(Regex("\\s+")) }.filter { it.isNotBlank() },
            )
        }.toList()

    /** SOAP body for GetDeviceInformation; optionally signed with a WS-UsernameToken from user-provided credentials. */
    fun getDeviceInformationRequest(credentials: Credentials? = null, nowIso: String = java.time.Instant.now().toString(), nonce: ByteArray = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }): String {
        val security = credentials?.let { c ->
            val digest = usernameTokenDigest(nonce, nowIso, c.password)
            """<s:Header><Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${xmlEscape(c.username)}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">${Base64.getEncoder().encodeToString(nonce)}</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$nowIso</Created></UsernameToken></Security></s:Header>"""
        }.orEmpty()
        return """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">$security<s:Body><GetDeviceInformation xmlns="http://www.onvif.org/ver10/device/wsdl"/></s:Body></s:Envelope>"""
    }

    fun usernameTokenDigest(nonce: ByteArray, created: String, password: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(nonce + created.toByteArray() + password.toByteArray()))

    fun parseDeviceInformation(httpStatus: Int, body: String): OnvifInfoResult {
        if (httpStatus == 401 || body.contains("NotAuthorized", true) || body.contains("FailedAuthentication", true))
            return OnvifInfoResult.NotAuthorized
        if (body.contains("Fault>", true)) return OnvifInfoResult.Failed("SOAP fault")
        if (!body.contains("GetDeviceInformationResponse", true)) return OnvifInfoResult.Failed("Unexpected response (HTTP $httpStatus)")
        return OnvifInfoResult.Info(OnvifDeviceInfo(
            tag(body, "Manufacturer").firstOrNull(), tag(body, "Model").firstOrNull(), tag(body, "FirmwareVersion").firstOrNull(),
            tag(body, "SerialNumber").firstOrNull(), tag(body, "HardwareId").firstOrNull(),
        ))
    }

    private fun xmlEscape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

// ---------------------------------------------------------------- SSDP / UPnP

data class SsdpResponse(val location: String?, val server: String?, val st: String?, val usn: String?)
data class UpnpDescription(val friendlyName: String?, val manufacturer: String?, val modelName: String?, val modelNumber: String?, val deviceType: String?)

object Ssdp {
    const val MULTICAST_GROUP = "239.255.255.250"
    const val PORT = 1900

    fun mSearch(st: String = "ssdp:all", mx: Int = 2) =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: $mx\r\nST: $st\r\nUSER-AGENT: Android/14 UPnP/1.1 CamRadar/1.0\r\n\r\n"

    fun parseResponse(raw: String): SsdpResponse? {
        val r = TextResponse.parse(raw) ?: return null
        if (r.status != 200) return null
        return SsdpResponse(r.header("location"), r.header("server"), r.header("st"), r.header("usn"))
    }

    fun parseDescription(xml: String): UpnpDescription {
        fun t(n: String) = Regex("""<$n>(.*?)</$n>""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()
        return UpnpDescription(t("friendlyName"), t("manufacturer"), t("modelName"), t("modelNumber"), t("deviceType"))
    }
}

// ---------------------------------------------------------------- RTSP

data class Credentials(val username: String, val password: String) {
    override fun toString() = "Credentials(username=$username, password=***)"
}

object Rtsp {
    fun options(url: String, cseq: Int = 1) = "OPTIONS $url RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: CamRadar/1.0\r\n\r\n"

    /** DESCRIBE only returns the SDP session description. CamRadar never sends SETUP/PLAY, so no media is received. */
    fun describe(url: String, cseq: Int = 2, authorization: String? = null) =
        "DESCRIBE $url RTSP/1.0\r\nCSeq: $cseq\r\nAccept: application/sdp\r\nUser-Agent: CamRadar/1.0\r\n" +
            (authorization?.let { "Authorization: $it\r\n" } ?: "") + "\r\n"

    fun publicMethods(r: TextResponse): Set<String> =
        r.header("public")?.split(',')?.map { it.trim().uppercase() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()
}

// ---------------------------------------------------------------- HTTP digest / basic (for user-supplied credentials only)

data class DigestChallenge(val realm: String, val nonce: String, val qop: String?, val opaque: String?, val algorithm: String?)

object HttpAuth {
    fun parseChallenges(headers: List<String>): Pair<DigestChallenge?, String?> {
        var digest: DigestChallenge? = null
        var basicRealm: String? = null
        headers.forEach { h ->
            val scheme = h.substringBefore(' ').trim()
            val params = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]+))""").findAll(h.substringAfter(' ')).associate {
                it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] })
            }
            when {
                scheme.equals("Digest", true) && params["nonce"] != null ->
                    digest = DigestChallenge(params["realm"].orEmpty(), params["nonce"]!!, params["qop"], params["opaque"], params["algorithm"])
                scheme.equals("Basic", true) -> basicRealm = params["realm"].orEmpty()
            }
        }
        return digest to basicRealm
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun digestAuthorization(c: Credentials, ch: DigestChallenge, method: String, uri: String, cnonce: String = UUID.randomUUID().toString().replace("-", "").take(16), nc: String = "00000001"): String {
        val ha1 = md5("${c.username}:${ch.realm}:${c.password}")
        val ha2 = md5("$method:$uri")
        val useQop = ch.qop?.split(',')?.map { it.trim() }?.contains("auth") == true
        val response = if (useQop) md5("$ha1:${ch.nonce}:$nc:$cnonce:auth:$ha2") else md5("$ha1:${ch.nonce}:$ha2")
        return buildString {
            append("Digest username=\"${c.username}\", realm=\"${ch.realm}\", nonce=\"${ch.nonce}\", uri=\"$uri\", response=\"$response\"")
            if (useQop) append(", qop=auth, nc=$nc, cnonce=\"$cnonce\"")
            ch.opaque?.let { append(", opaque=\"$it\"") }
            ch.algorithm?.let { append(", algorithm=$it") }
        }
    }

    fun basicAuthorization(c: Credentials) = "Basic " + Base64.getEncoder().encodeToString("${c.username}:${c.password}".toByteArray())
}

// ---------------------------------------------------------------- fingerprinting

data class Fingerprint(val manufacturer: String?, val model: String?, val looksLikeCamera: Boolean)

object Fingerprinter {
    private val VENDORS = linkedMapOf(
        "hikvision" to "Hikvision", "dahua" to "Dahua", "axis" to "Axis", "hanwha" to "Hanwha Vision", "samsung techwin" to "Hanwha Vision",
        "wisenet" to "Hanwha Vision", "reolink" to "Reolink", "amcrest" to "Amcrest", "foscam" to "Foscam", "uniview" to "Uniview",
        "unv" to "Uniview", "vivotek" to "Vivotek", "bosch" to "Bosch", "mobotix" to "Mobotix", "tp-link" to "TP-Link", "tapo" to "TP-Link",
        "ubiquiti" to "Ubiquiti", "unifi protect" to "Ubiquiti", "ezviz" to "EZVIZ", "lorex" to "Lorex", "swann" to "Swann",
        "hanwha" to "Hanwha Vision", "geovision" to "GeoVision", "milesight" to "Milesight", "tiandy" to "Tiandy", "imou" to "Imou",
    )
    private val CAMERA_HINTS = listOf("camera", "ipcam", "ip cam", "nvr", "dvr", "webcam", "network video", "netcam", "surveillance", "cctv", "web service", "app-webs", "hipcam", "boa/")

    fun identify(vararg signals: String?): Fingerprint {
        val text = signals.filterNotNull().joinToString(" ").lowercase()
        val vendor = VENDORS.entries.firstOrNull { (k, _) -> Regex("""(^|[^a-z])${Regex.escape(k)}([^a-z]|$)""").containsMatchIn(text) }?.value
        return Fingerprint(vendor, null, vendor != null || CAMERA_HINTS.any { it in text })
    }

    fun htmlTitle(body: String) = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        .find(body)?.groupValues?.get(1)?.trim()?.take(120)
}
