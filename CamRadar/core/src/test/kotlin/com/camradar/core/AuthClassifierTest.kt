package com.camradar.core

import com.camradar.core.discovery.AuthClassifier
import com.camradar.core.discovery.AuthEvidence
import com.camradar.core.discovery.OnvifDeviceInfo
import com.camradar.core.discovery.OnvifInfoResult
import com.camradar.core.model.AuthClassification.AUTH_REQUIRED
import com.camradar.core.model.AuthClassification.NO_AUTH_DETECTED
import com.camradar.core.model.AuthClassification.UNKNOWN
import com.camradar.core.model.AuthClassification.VERIFIED_WITH_USER_CREDENTIALS
import com.camradar.core.model.AuthScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthClassifierTest {
    private fun c(vararg e: AuthEvidence) = AuthClassifier.classify(e.toList())

    @Test fun noEvidenceIsUnknown() = assertEquals(UNKNOWN, c().classification)

    @Test fun rtsp401IsAuthRequiredOnStream() {
        val a = c(AuthEvidence.RtspDescribe(401, false))
        assertEquals(AUTH_REQUIRED, a.classification); assertEquals(AuthScope.VIDEO_STREAM, a.scope)
    }

    @Test fun rtspSdpWithoutCredsIsNoAuthDetected() =
        assertEquals(NO_AUTH_DETECTED, c(AuthEvidence.RtspDescribe(200, true)).classification)

    @Test fun rtsp200WithoutSdpIsNotNoAuth() =
        assertEquals(UNKNOWN, c(AuthEvidence.RtspDescribe(200, false)).classification)

    @Test fun openWebInterfaceIsNotAnOpenStream() {
        val a = c(AuthEvidence.HttpRoot(200, false))
        assertEquals(UNKNOWN, a.classification); assertEquals(AuthScope.WEB_INTERFACE, a.scope)
    }

    @Test fun webLoginChallengeIsAuthRequiredScopedToWeb() {
        val a = c(AuthEvidence.HttpRoot(401, true))
        assertEquals(AUTH_REQUIRED, a.classification); assertEquals(AuthScope.WEB_INTERFACE, a.scope)
    }

    @Test fun streamFindingOutranksWebFinding() {
        val a = c(AuthEvidence.HttpRoot(200, false), AuthEvidence.RtspDescribe(401, false))
        assertEquals(AuthScope.VIDEO_STREAM, a.scope)
    }

    @Test fun onvifNotAuthorized() =
        assertEquals(AUTH_REQUIRED, c(AuthEvidence.OnvifInfo(OnvifInfoResult.NotAuthorized)).classification)

    @Test fun onvifOpenInfoDoesNotClaimStreamOpen() {
        val a = c(AuthEvidence.OnvifInfo(OnvifInfoResult.Info(OnvifDeviceInfo("A", "B", null, null, null))))
        assertEquals(NO_AUTH_DETECTED, a.classification); assertEquals(AuthScope.DEVICE_SERVICE, a.scope)
        assertTrue(a.rationale.contains("Stream access was not tested"))
    }

    @Test fun acceptedUserCredentialsWin() =
        assertEquals(VERIFIED_WITH_USER_CREDENTIALS, c(AuthEvidence.RtspDescribe(401, false), AuthEvidence.UserCredentialCheck(AuthScope.VIDEO_STREAM, true)).classification)

    @Test fun rejectedUserCredentialsKeepAuthRequired() {
        val a = c(AuthEvidence.RtspDescribe(401, false), AuthEvidence.UserCredentialCheck(AuthScope.VIDEO_STREAM, false))
        assertEquals(AUTH_REQUIRED, a.classification); assertTrue(a.rationale.contains("rejected"))
    }
}
