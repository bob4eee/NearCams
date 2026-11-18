package com.camradar.core.service

import com.camradar.core.geo.Geo
import com.camradar.core.geo.LatLon
import com.camradar.core.model.Camera
import com.camradar.core.model.LocationConfidence

/** Storage abstraction implemented by Room in the app, and by an in-memory fake in tests. */
interface CameraStore {
    suspend fun upsert(cameras: List<Camera>)
    suspend fun all(): List<Camera>
}

/** A camera together with its distance from the user, for list/map display. */
data class NearbyCamera(val camera: Camera, val distanceMeters: Double?)

data class GeoResult(val cameras: List<NearbyCamera>, val fromCache: Boolean, val error: String?)

/**
 * Fetches registered camera locations near the user. On network failure, it falls back
 * to cached records so the app keeps working offline.
 */
class GeoDiscoveryService(
    private val remote: suspend (LatLon, Int) -> List<Camera>,
    private val store: CameraStore,
) {
    suspend fun refresh(center: LatLon, radiusMeters: Int): GeoResult {
        val error = try {
            store.upsert(remote(center, radiusMeters)); null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "Offline or source unavailable: ${e.message ?: e::class.simpleName}"
        }
        return GeoResult(nearby(store.all(), center, radiusMeters), fromCache = error != null, error = error)
    }

    companion object {
        /**
         * Filters geographic records to the radius and sorts by distance. Local-network devices are always
         * kept (they are "near" by definition). IP-geolocated records are kept only inside the radius and the
         * UI marks them approximate; their distance is a city-level estimate, not the camera's position.
         */
        fun nearby(all: List<Camera>, center: LatLon, radiusMeters: Int): List<NearbyCamera> = all.mapNotNull { c ->
            val loc = c.location
            val d = loc?.let { Geo.distanceMeters(center, it) }
            when {
                c.networkId != null -> NearbyCamera(c, d)
                d == null -> null
                d <= radiusMeters -> NearbyCamera(c, d)
                else -> null
            }
        }.sortedWith(compareBy<NearbyCamera, Double?>(nullsLast()) { it.distanceMeters })

        fun isApproximate(c: Camera) = c.locationConfidence == LocationConfidence.APPROXIMATE_IP_GEOLOCATION ||
            c.locationConfidence == LocationConfidence.APPROXIMATE_REGISTERED ||
            c.locationConfidence == LocationConfidence.USER_LOCATION_AT_DISCOVERY
    }
}
