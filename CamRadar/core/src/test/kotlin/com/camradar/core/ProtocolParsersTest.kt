package com.camradar.core

import com.camradar.core.discovery.Credentials
import com.camradar.core.discovery.DigestChallenge
import com.camradar.core.discovery.Fingerprinter
import com.camradar.core.discovery.HttpAuth
import com.camradar.core.discovery.Onvif
import com.camradar.core.discovery.OnvifInfoResult
import com.camradar.core.discovery.Rtsp
import com.camradar.core.discovery.Ssdp
import com.camradar.core.discovery.TextResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolParsersTest {
    @Test fun onvifProbeMatch() {
        val xml = """<SOAP-ENV:Envelope xmlns:SOAP-ENV="http://www.w3.org/2003/05/soap-envelope" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing">
          <SOAP-ENV:Body><d:ProbeMatches><d:ProbeMatch>
            <wsa:EndpointReference><wsa:Address>urn:uuid:abc-123</wsa:Address></wsa:EndpointReference>
            <d:Types>dn:NetworkVideoTransmitter tds:Device</d:Types>
            <d:Scopes>onvif://www.onvif.org/type/video_encoder onvif://www.onvif.org/name/HIKVISION%20DS-2CD2143 onvif://www.onvif.org/hardware/DS-2CD2143G2-I</d:Scopes>
            <d:XAddrs>http://192.168.1.64/onvif/device_service http://[fe80::1]/onvif/device_service</d:XAddrs>
          </d:ProbeMatch></d:ProbeMatches></SOAP-ENV:Body></SOAP-ENV:Envelope>"""
        val m = Onvif.parseProbeMatches(xml).single()
        assertEquals("urn:uuid:abc-123", m.endpoint)
        assertEquals("HIKVISION DS-2CD2143", m.name)
        assertEquals("DS-2CD2143G2-I", m.hardware)
        assertEquals(2, m.xAddrs.size)
        assertTrue(m.isVideoDevice)
    }

    @Test fun onvifProbeMessageIsWellFormedAndTargetsCameras() {
        val p = Onvif.probeMessage("uuid:1")
        assertTrue(p.contains("NetworkVideoTransmitter")); assertTrue(p.contains("<w:MessageID>uuid:1</w:MessageID>"))
    }

    @Test fun onvifDeviceInfo() {
        val ok = """<env:Body><tds:GetDeviceInformationResponse><tds:Manufacturer>AXIS</tds:Manufacturer><tds:Model>P3245-V</tds:Model><tds:FirmwareVersion>10.12</tds:FirmwareVersion></tds:GetDeviceInformationResponse></env:Body>"""
        val r = Onvif.parseDeviceInformation(200, ok) as OnvifInfoResult.Info
        assertEquals("AXIS", r.info.manufacturer); assertEquals("P3245-V", r.info.model)
        val fault = """<env:Fault><env:Code><env:Subcode><env:Value>ter:NotAuthorized</env:Value></env:Subcode></env:Code></env:Fault>"""
        assertEquals(OnvifInfoResult.NotAuthorized, Onvif.parseDeviceInformation(400, fault))
        assertEquals(OnvifInfoResult.NotAuthorized, Onvif.parseDeviceInformation(401, ""))
        assertTrue(Onvif.parseDeviceInformation(200, "<html/>") is OnvifInfoResult.Failed)
    }

    @Test fun wsUsernameTokenDigestKnownVector() {
        // Expected value computed independently with Python hashlib/base64.
        val nonce = java.util.Base64.getDecoder().decode("LKqI6G/AikKCQrN0zqZFlg==")
        val d = Onvif.usernameTokenDigest(nonce, "2010-09-16T07:50:45Z", "userpassword")
        assertEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=", d)
    }

    @Test fun ssdpResponseAndDescription() {
        val raw = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: http://192.168.1.70:49152/desc.xml\r\nSERVER: Linux/3.10 UPnP/1.0 Reolink/1.0\r\nST: upnp:rootdevice\r\nUSN: uuid:x::upnp:rootdevice\r\n\r\n"
        val r = Ssdp.parseResponse(raw)!!
        assertEquals("http://192.168.1.70:49152/desc.xml", r.location)
        assertTrue(r.server!!.contains("Reolink"))
        assertNull(Ssdp.parseResponse("garbage"))
        val d = Ssdp.parseDescription("<root><device><deviceType>urn:schemas-upnp-org:device:Basic:1</deviceType><friendlyName>Front Door</friendlyName><manufacturer>Reolink</manufacturer><modelName>RLC-510A</modelName></device></root>")
        assertEquals("Front Door", d.friendlyName); assertEquals("RLC-510A", d.modelName)
    }

    @Test fun rtspResponses() {
        val opt = TextResponse.parse("RTSP/1.0 200 OK\r\nCSeq: 1\r\nPublic: OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN\r\nServer: Hikvision RTSP Server\r\n\r\n")!!
        assertEquals(200, opt.status); assertTrue("DESCRIBE" in Rtsp.publicMethods(opt))
        val unauth = TextResponse.parse("RTSP/1.0 401 Unauthorized\r\nCSeq: 2\r\nWWW-Authenticate: Digest realm=\"IP Camera\", nonce=\"abc\"\r\nWWW-Authenticate: Basic realm=\"IP Camera\"\r\n\r\n")!!
        assertEquals(2, unauth.headersNamed("WWW-Authenticate").size)
        val (digest, basic) = HttpAuth.parseChallenges(unauth.headersNamed("www-authenticate"))
        assertEquals("abc", digest!!.nonce); assertEquals("IP Camera", basic)
        assertFalse(Rtsp.describe("rtsp://x/").contains("SETUP"))
        assertNull(TextResponse.parse("SSH-2.0-OpenSSH_9.6"))
    }

    @Test fun digestRfc2617Vector() {
        // RFC 2617 section 3.5 example.
        val ch = DigestChallenge("testrealm@host.com", "dcd98b7102dd2f0e8b11d0f600bfb0c093", "auth", "5ccc069c403ebaf9f0171e9517f40e41", null)
        val h = HttpAuth.digestAuthorization(Credentials("Mufasa", "Circle Of Life"), ch, "GET", "/dir/index.html", cnonce = "0a4f113b")
        assertTrue(h, h.contains("response=\"6629fae49393a05397450978507c4ef1\""))
    }

    @Test fun credentialsNeverPrintPassword() = assertFalse(Credentials("admin", "s3cret").toString().contains("s3cret"))

    @Test fun fingerprinting() {
        assertEquals("Hikvision", Fingerprinter.identify("App-webs/", "Hikvision Digital").manufacturer)
        assertEquals("Axis", Fingerprinter.identify(null, "AXIS P3245 Network Camera").manufacturer)
        assertTrue(Fingerprinter.identify("Boa/0.94", null).looksLikeCamera)
        assertFalse(Fingerprinter.identify("nginx", "Router admin").looksLikeCamera)
        assertNull(Fingerprinter.identify("taxis-dispatch").manufacturer) // no substring false positive on "axis"
        assertEquals("Login", Fingerprinter.htmlTitle("<html><TITLE> Login </TITLE></html>"))
    }
}
