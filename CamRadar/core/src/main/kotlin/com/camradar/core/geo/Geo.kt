package com.camradar.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "longitude out of range: $lon" }
    }
}

enum class SearchRadius(val meters: Int, val label: String) {
    M100(100, "100 m"), M250(250, "250 m"), M500(500, "500 m"), KM1(1_000, "1 km"), KM5(5_000, "5 km");
}

enum class AccuracyLevel(val label: String) {
    HIGH("High (≤ 10 m)"), MEDIUM("Medium (≤ 50 m)"), LOW("Low (≤ 200 m)"), POOR("Poor (> 200 m)");

    companion object {
        fun fromMeters(accuracy: Float): AccuracyLevel = when {
            accuracy <= 10f -> HIGH
            accuracy <= 50f -> MEDIUM
            accuracy <= 200f -> LOW
            else -> POOR
        }
    }
}

data class UserFix(val position: LatLon, val accuracyMeters: Float, val timeEpochMs: Long, val isMock: Boolean = false) {
    val accuracyLevel get() = AccuracyLevel.fromMeters(accuracyMeters)
}

data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double)

object Geo {
    private const val EARTH_RADIUS_M = 6_371_008.8

    fun distanceMeters(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun boundingBox(center: LatLon, radiusMeters: Int): BoundingBox {
        val dLat = Math.toDegrees(radiusMeters / EARTH_RADIUS_M)
        val dLon = Math.toDegrees(radiusMeters / (EARTH_RADIUS_M * cos(Math.toRadians(center.lat)).coerceAtLeast(1e-6)))
        return BoundingBox(
            (center.lat - dLat).coerceAtLeast(-90.0), (center.lon - dLon).coerceAtLeast(-180.0),
            (center.lat + dLat).coerceAtMost(90.0), (center.lon + dLon).coerceAtMost(180.0),
        )
    }

    fun isWithin(center: LatLon, point: LatLon, radiusMeters: Int) = distanceMeters(center, point) <= radiusMeters

    fun formatDistance(meters: Double): String =
        if (meters < 1_000) "${meters.roundToInt()} m" else "%.1f km".format(java.util.Locale.US, meters / 1_000)
}
