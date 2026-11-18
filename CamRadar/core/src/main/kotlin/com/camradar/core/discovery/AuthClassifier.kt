package com.camradar.core.discovery

import com.camradar.core.model.AuthAssessment
import com.camradar.core.model.AuthClassification
import com.camradar.core.model.AuthScope

/** One observation from an approved, non-intrusive check. */
sealed interface AuthEvidence {
    /** RTSP DESCRIBE without credentials. 200+SDP = stream description is open; 401 = auth required. */
    data class RtspDescribe(val status: Int, val hasSdp: Boolean) : AuthEvidence
    /** ONVIF GetDeviceInformation without credentials. */
    data class OnvifInfo(val result: OnvifInfoResult) : AuthEvidence
    /** HTTP GET / — this only ever describes the web UI, never the video stream. */
    data class HttpRoot(val status: Int, val hasAuthChallenge: Boolean) : AuthEvidence
    /** One check made with credentials the user typed in for their own device. */
    data class UserCredentialCheck(val scope: AuthScope, val accepted: Boolean) : AuthEvidence
}

object AuthClassifier {
    fun classify(evidence: List<AuthEvidence>): AuthAssessment {
        // 1. Verified with the user's own credentials beats everything else.
        evidence.filterIsInstance<AuthEvidence.UserCredentialCheck>().firstOrNull { it.accepted }?.let {
            return AuthAssessment(AuthClassification.VERIFIED_WITH_USER_CREDENTIALS, it.scope, "Your credentials were accepted by the ${it.scope.label}.")
        }

        val rtsp = evidence.filterIsInstance<AuthEvidence.RtspDescribe>()
        val onvif = evidence.filterIsInstance<AuthEvidence.OnvifInfo>()
        val http = evidence.filterIsInstance<AuthEvidence.HttpRoot>()
        val rejected = evidence.filterIsInstance<AuthEvidence.UserCredentialCheck>().any { !it.accepted }
        val rejectedNote = if (rejected) " Your credentials were rejected." else ""

        // 2. Stream-level findings are the most meaningful.
        if (rtsp.any { it.status == 401 })
            return AuthAssessment(AuthClassification.AUTH_REQUIRED, AuthScope.VIDEO_STREAM, "RTSP DESCRIBE returned 401 Unauthorized.$rejectedNote")
        if (rtsp.any { it.status == 200 && it.hasSdp })
            return AuthAssessment(AuthClassification.NO_AUTH_DETECTED, AuthScope.VIDEO_STREAM,
                "RTSP DESCRIBE returned a session description without credentials. No media was requested.$rejectedNote")

        // 3. Device service.
        if (onvif.any { it.result is OnvifInfoResult.NotAuthorized })
            return AuthAssessment(AuthClassification.AUTH_REQUIRED, AuthScope.DEVICE_SERVICE, "ONVIF GetDeviceInformation requires authentication.$rejectedNote")
        if (onvif.any { it.result is OnvifInfoResult.Info })
            return AuthAssessment(AuthClassification.NO_AUTH_DETECTED, AuthScope.DEVICE_SERVICE,
                "ONVIF device information is readable without credentials. Stream access was not tested.$rejectedNote")

        // 4. Web UI only. An open login page is NOT an open stream, so a 200 stays UNKNOWN.
        if (http.any { it.status == 401 || it.hasAuthChallenge })
            return AuthAssessment(AuthClassification.AUTH_REQUIRED, AuthScope.WEB_INTERFACE, "The web interface requests HTTP authentication. Stream status unknown.$rejectedNote")
        if (http.any { it.status in 200..399 })
            return AuthAssessment(AuthClassification.UNKNOWN, AuthScope.WEB_INTERFACE,
                "The web interface is reachable, but that does not show whether the video stream is protected.$rejectedNote")

        return AuthAssessment(AuthClassification.UNKNOWN, AuthScope.NONE, "No approved check produced a conclusive result.$rejectedNote")
    }
}
