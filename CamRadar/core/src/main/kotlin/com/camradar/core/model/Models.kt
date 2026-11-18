package com.camradar.core.model

import com.camradar.core.geo.LatLon

/** Where a camera record came from. Shown to the user on every entry. */
enum class DiscoverySource(val label: String) {
    OSM_SURVEILLANCE("OpenStreetMap surveillance registry"),
    IMPORTED_REGISTRY("Imported camera registry (CSV)"),
    LOCAL_ONVIF("Local network: ONVIF WS-Discovery"),
    LOCAL_SSDP("Local network: UPnP/SSDP"),
    LOCAL_PORT_PROBE("Local network: service probe"),
    MANUAL("Added manually"),
}

/**
 * How trustworthy the geographic position is.
 * IP geolocation is never treated as a physical camera location.
 */
enum class LocationConfidence(val label: String) {
    EXACT_REGISTERED("Exact registered coordinates"),
    APPROXIMATE_REGISTERED("Approximate registry coordinates"),
    USER_LOCATION_AT_DISCOVERY("Phone position when found on local network"),
    APPROXIMATE_IP_GEOLOCATION("Approximate IP geolocation (city level, not the camera's position)"),
    UNKNOWN("Location unknown"),
}

enum class CameraCategory(val label: String) {
    PUBLIC_SURVEILLANCE("Public CCTV"),
    TRAFFIC("Traffic camera"),
    PRIVATE_SURVEILLANCE("Private CCTV"),
    WEBCAM("Webcam"),
    LOCAL_NETWORK("Local network camera"),
    OTHER("Other"),
}

enum class Protocol { ONVIF, RTSP, HTTP, HTTPS, UPNP }

enum class AuthClassification(val label: String) {
    AUTH_REQUIRED("Authentication required"),
    NO_AUTH_DETECTED("No authentication detected through approved checks"),
    UNKNOWN("Authentication status unknown"),
    VERIFIED_WITH_USER_CREDENTIALS("Authentication verified using your credentials"),
}

/** Which surface an authentication finding applies to — a web UI is not a video stream. */
enum class AuthScope(val label: String) {
    VIDEO_STREAM("RTSP stream description"),
    DEVICE_SERVICE("ONVIF device service"),
    WEB_INTERFACE("HTTP web interface only"),
    NONE("No approved check succeeded"),
}

data class AuthAssessment(
    val classification: AuthClassification,
    val scope: AuthScope,
    val rationale: String,
)

data class Camera(
    val id: String,
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val ipAddress: String? = null,
    val location: LatLon? = null,
    val locationConfidence: LocationConfidence = LocationConfidence.UNKNOWN,
    val category: CameraCategory = CameraCategory.OTHER,
    val protocols: Set<Protocol> = emptySet(),
    val auth: AuthAssessment = AuthAssessment(AuthClassification.UNKNOWN, AuthScope.NONE, "Not assessed"),
    val source: DiscoverySource,
    val sourceDetail: String? = null,
    val networkId: String? = null,
    val lastSeenEpochMs: Long,
)
