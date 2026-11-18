package com.camradar.core.geo

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Foreground-only stream of position fixes. The app's implementation wraps FusedLocationProviderClient. */
interface LocationSource {
    fun fixes(): Flow<UserFix>
}

/**
 * Replays a scripted route. Used by GPS simulation tests and selectable in debug builds
 * ("Simulated GPS" in settings) so the app can be exercised in an emulator with no real location.
 */
class SimulatedLocationSource(
    private val route: List<LatLon>,
    private val accuracyMeters: List<Float> = listOf(8f),
    private val intervalMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : LocationSource {
    init { require(route.isNotEmpty()) }

    override fun fixes(): Flow<UserFix> = flow {
        route.forEachIndexed { i, p ->
            emit(UserFix(p, accuracyMeters[i % accuracyMeters.size], clock(), isMock = true))
            if (i < route.lastIndex) delay(intervalMs)
        }
    }

    companion object {
        /** Straight-line walk from a to b in n steps. */
        fun walk(a: LatLon, b: LatLon, steps: Int): List<LatLon> =
            (0..steps).map { i -> val t = i.toDouble() / steps; LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t) }
    }
}
