package com.opencams.nearby.data

/** How the live view is delivered. */
enum class FeedType {
    /** HLS (.m3u8), DASH, or RTSP stream -> ExoPlayer. */
    STREAM,
    /** A still image URL that refreshes (snapshot / MJPEG-style) -> polled with Coil. */
    IMAGE
}

/**
 * A publicly published camera feed. These come either from the bundled catalog
 * (assets/cameras.json) or from the Windy Webcams API — in both cases feeds the
 * operator has deliberately made public.
 */
data class Camera(
    val id: String,
    val title: String,
    val location: String,
    val lat: Double,
    val lon: Double,
    val feedType: FeedType,
    /** Stream URL (STREAM) or still-image URL (IMAGE). */
    val url: String,
    /** Attribution / source page the operator publishes. */
    val source: String? = null,
    /**
     * Optional ONVIF/vendor PTZ control endpoint the operator exposes publicly.
     * Null for the vast majority of feeds, which are view-only.
     */
    val ptzBaseUrl: String? = null,
    /** Straight-line distance from the user in km, filled in at query time. */
    var distanceKm: Double? = null
) {
    val hasControls: Boolean get() = ptzBaseUrl != null
}
