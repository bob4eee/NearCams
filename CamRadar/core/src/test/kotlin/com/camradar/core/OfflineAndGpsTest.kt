package com.camradar.core

import com.camradar.core.geo.Geo
import com.camradar.core.geo.LatLon
import com.camradar.core.geo.SimulatedLocationSource
import com.camradar.core.model.Camera
import com.camradar.core.model.DiscoverySource
import com.camradar.core.model.LocationConfidence
import com.camradar.core.service.CameraStore
import com.camradar.core.service.GeoDiscoveryService
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OfflineAndGpsTest {
    class MemStore : CameraStore {
        val map = linkedMapOf<String, Camera>()
        override suspend fun upsert(cameras: List<Camera>) { cameras.forEach { map[it.id] = it } }
        override suspend fun all() = map.values.toList()
    }

    private val center = LatLon(42.6977, 23.3219)
    private fun cam(id: String, dLat: Double, conf: LocationConfidence = LocationConfidence.EXACT_REGISTERED) =
        Camera(id, id, location = LatLon(center.lat + dLat, center.lon), locationConfidence = conf, source = DiscoverySource.OSM_SURVEILLANCE, lastSeenEpochMs = 0)

    @Test fun onlineRefreshStoresAndFilters() = runBlocking {
        val store = MemStore()
        val svc = GeoDiscoveryService({ _, _ -> listOf(cam("near", 0.001), cam("far", 0.1)) }, store)
        val r = svc.refresh(center, 500)
        assertFalse(r.fromCache); assertNull(r.error)
        assertEquals(listOf("near"), r.cameras.map { it.camera.id })
        assertEquals(2, store.map.size)
    }

    @Test fun offlineFallsBackToCache() = runBlocking {
        val store = MemStore().apply { upsert(listOf(cam("cached", 0.002))) }
        val svc = GeoDiscoveryService({ _, _ -> throw IOException("Unable to resolve host") }, store)
        val r = svc.refresh(center, 1000)
        assertTrue(r.fromCache); assertNotNull(r.error)
        assertEquals(listOf("cached"), r.cameras.map { it.camera.id })
    }

    @Test fun offlineWithEmptyCacheIsEmptyNotCrash() = runBlocking {
        val r = GeoDiscoveryService({ _, _ -> throw IOException("offline") }, MemStore()).refresh(center, 100)
        assertTrue(r.cameras.isEmpty()); assertTrue(r.fromCache)
    }

    @Test fun sortedByDistanceAndLocalAlwaysIncluded() {
        val local = Camera("lan", "lan", networkId = "n", source = DiscoverySource.LOCAL_ONVIF, lastSeenEpochMs = 0)
        val list = GeoDiscoveryService.nearby(listOf(cam("b", 0.003), local, cam("a", 0.001)), center, 1000)
        assertEquals(listOf("a", "b", "lan"), list.map { it.camera.id })
    }

    @Test fun ipGeolocationIsFlaggedApproximate() {
        val c = cam("ipgeo", 0.0001, LocationConfidence.APPROXIMATE_IP_GEOLOCATION)
        assertTrue(GeoDiscoveryService.isApproximate(c))
        assertFalse(GeoDiscoveryService.isApproximate(cam("x", 0.0)))
    }

    @Test fun simulatedWalkChangesNearbySet() = runBlocking {
        val start = LatLon(42.6977, 23.3219)
        val end = LatLon(42.7067, 23.3219) // ~1 km north
        val gps = SimulatedLocationSource(SimulatedLocationSource.walk(start, end, 4), listOf(5f, 60f), intervalMs = 1)
        val fixes = gps.fixes().toList()
        assertEquals(5, fixes.size)
        assertTrue(fixes.all { it.isMock })
        assertEquals(end, fixes.last().position)
        assertEquals(1_000.0, Geo.distanceMeters(start, fixes.last().position), 15.0)

        val cameras = listOf(cam("atStart", 0.0), Camera("atEnd", "atEnd", location = end, locationConfidence = LocationConfidence.EXACT_REGISTERED, source = DiscoverySource.OSM_SURVEILLANCE, lastSeenEpochMs = 0))
        val nearStart = GeoDiscoveryService.nearby(cameras, fixes.first().position, 250).map { it.camera.id }
        val nearEnd = GeoDiscoveryService.nearby(cameras, fixes.last().position, 250).map { it.camera.id }
        assertEquals(listOf("atStart"), nearStart); assertEquals(listOf("atEnd"), nearEnd)
    }
}
