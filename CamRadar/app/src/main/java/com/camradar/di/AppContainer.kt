package com.camradar.di

import android.content.Context
import com.camradar.core.discovery.LocalDiscoveryEngine
import com.camradar.core.discovery.NetTransport
import com.camradar.core.discovery.SocketTransport
import com.camradar.core.geo.LatLon
import com.camradar.core.geo.LocationSource
import com.camradar.core.geo.OverpassSource
import com.camradar.core.geo.SimulatedLocationSource
import com.camradar.core.geo.UrlConnectionFetcher
import com.camradar.core.model.Camera
import com.camradar.core.service.GeoDiscoveryService
import com.camradar.data.CamRadarDatabase
import com.camradar.data.CameraRepository
import com.camradar.data.SettingsStore
import com.camradar.location.FusedLocationSource
import com.camradar.net.AndroidNetworkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Manual DI. Tests replace pieces through [Overrides] before the Activity starts. */
class AppContainer(context: Context, o: Overrides = Overrides()) {
    data class Overrides(
        val inMemoryDb: Boolean = false,
        val transport: NetTransport? = null,
        val remoteGeo: (suspend (LatLon, Int) -> List<Camera>)? = null,
        val location: LocationSource? = null,
    )

    val db = CamRadarDatabase.build(context, o.inMemoryDb)
    val repository = CameraRepository(db)
    val settings = SettingsStore(context)
    val networkInfo = AndroidNetworkInfo(context)
    val engine = LocalDiscoveryEngine(o.transport ?: SocketTransport())
    private val overpass = OverpassSource(UrlConnectionFetcher())
    private val remoteGeo: suspend (LatLon, Int) -> List<Camera> =
        o.remoteGeo ?: { c, r -> withContext(Dispatchers.IO) { overpass.fetch(c, r) } }
    val geoService = GeoDiscoveryService(remoteGeo, repository)
    val realLocation: LocationSource = o.location ?: FusedLocationSource(context)

    /** Debug/emulator: a short walk through central Sofia. */
    val simulatedLocation: LocationSource = SimulatedLocationSource(
        SimulatedLocationSource.walk(LatLon(42.6964, 23.3260), LatLon(42.6935, 23.3326), 30), listOf(6f, 15f, 40f), intervalMs = 3_000,
    )
}
