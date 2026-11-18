package com.camradar.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.camradar.CamRadarApp
import com.camradar.core.discovery.AuthEvidence
import com.camradar.core.discovery.AuthorizedNetwork
import com.camradar.core.discovery.Credentials
import com.camradar.core.discovery.CurrentNetwork
import com.camradar.core.discovery.DiscoveryEvent
import com.camradar.core.discovery.GuardDecision
import com.camradar.core.discovery.NetworkGuard
import com.camradar.core.geo.LatLon
import com.camradar.core.geo.RegistryCsvParser
import com.camradar.core.geo.SearchRadius
import com.camradar.core.geo.UserFix
import com.camradar.core.model.CameraCategory
import com.camradar.core.model.DiscoverySource
import com.camradar.core.service.GeoDiscoveryService
import com.camradar.core.service.NearbyCamera
import com.camradar.data.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Filters(
    val categories: Set<CameraCategory> = CameraCategory.entries.toSet(),
    val sources: Set<SourceGroup> = SourceGroup.entries.toSet(),
)

enum class SourceGroup(val label: String) { PUBLIC_REGISTRY("Public registry"), LOCAL_NETWORK("Local network"), IMPORTED("Imported") }

fun DiscoverySource.group() = when (this) {
    DiscoverySource.OSM_SURVEILLANCE -> SourceGroup.PUBLIC_REGISTRY
    DiscoverySource.IMPORTED_REGISTRY, DiscoverySource.MANUAL -> SourceGroup.IMPORTED
    else -> SourceGroup.LOCAL_NETWORK
}

sealed interface ScanState {
    data object Idle : ScanState
    data class Running(val phase: String, val scanned: Int = 0, val total: Int = 0) : ScanState
    data class Done(val message: String) : ScanState
}

data class UiState(
    val hasLocationPermission: Boolean = false,
    val fix: UserFix? = null,
    /** Center used when location is unavailable: wherever the user pans the map. */
    val manualCenter: LatLon? = null,
    val settings: AppSettings = AppSettings(),
    val filters: Filters = Filters(),
    val nearby: List<NearbyCamera> = emptyList(),
    val selectedId: String? = null,
    val geoStatus: String? = null,
    val geoLoading: Boolean = false,
    val network: CurrentNetwork? = null,
    val guard: GuardDecision = GuardDecision.Denied("Not checked"),
    val authorized: List<AuthorizedNetwork> = emptyList(),
    val scan: ScanState = ScanState.Idle,
    val message: String? = null,
) {
    val center: LatLon? get() = fix?.position ?: manualCenter
    val visible: List<NearbyCamera> get() = nearby.filter { it.camera.category in filters.categories && it.camera.source.group() in filters.sources }
    val selected: NearbyCamera? get() = nearby.firstOrNull { it.camera.id == selectedId }
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as CamRadarApp).container
    private val local = MutableStateFlow(UiState(settings = c.settings.state.value))
    private val evidence = mutableMapOf<String, List<AuthEvidence>>()
    private var scanJob: Job? = null
    private var locationJob: Job? = null

    val state: StateFlow<UiState> = combine(local, c.repository.cameras, c.repository.authorizedNetworks) { s, cams, auth ->
        val center = s.center
        s.copy(
            nearby = if (center != null) GeoDiscoveryService.nearby(cams, center, s.settings.radius.meters)
            else cams.filter { it.networkId != null }.map { NearbyCamera(it, null) },
            authorized = auth,
            guard = NetworkGuard.evaluate(s.network, auth),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), local.value)

    // ------------------------------------------------------------ location (foreground only)

    fun onPermissionResult(granted: Boolean) { local.update { it.copy(hasLocationPermission = granted) } }

    /** Called from the UI in onStart; [stopLocation] in onStop, so nothing runs in the background. */
    fun startLocation() {
        val s = local.value
        if (locationJob?.isActive == true || (!s.hasLocationPermission && !s.settings.simulatedGps)) return
        val source = if (s.settings.simulatedGps) c.simulatedLocation else c.realLocation
        locationJob = viewModelScope.launch {
            source.fixes().catch { e -> local.update { it.copy(message = "Location error: ${e.message}") } }.collect { fix ->
                val first = local.value.fix == null
                local.update { it.copy(fix = fix) }
                if (first) refreshGeo()
            }
        }
    }

    fun stopLocation() { locationJob?.cancel(); locationJob = null }

    fun setManualCenter(p: LatLon) { if (local.value.fix == null) local.update { it.copy(manualCenter = p) } }

    fun setSimulatedGps(on: Boolean) {
        c.settings.update { it.copy(simulatedGps = on) }
        local.update { it.copy(settings = c.settings.state.value, fix = null) }
        stopLocation(); startLocation()
    }

    // ------------------------------------------------------------ geographic discovery

    fun setRadius(r: SearchRadius) {
        c.settings.update { it.copy(radius = r) }
        local.update { it.copy(settings = c.settings.state.value) }
        refreshGeo()
    }

    fun setRegistryConsent(on: Boolean) {
        c.settings.update { it.copy(publicRegistryConsent = on) }
        local.update { it.copy(settings = c.settings.state.value) }
        if (on) refreshGeo()
    }

    fun refreshGeo() {
        val s = local.value
        val center = s.center ?: return
        if (!s.settings.publicRegistryConsent) {
            local.update { it.copy(geoStatus = "Public registry off. Enable it in Settings to load mapped cameras.") }; return
        }
        viewModelScope.launch {
            local.update { it.copy(geoLoading = true) }
            val r = c.geoService.refresh(center, s.settings.radius.meters)
            local.update {
                it.copy(geoLoading = false, geoStatus = r.error?.let { e -> "$e — showing cached results" }
                    ?: "Loaded ${r.cameras.count { n -> n.camera.networkId == null }} mapped cameras (© OpenStreetMap contributors)")
            }
        }
    }

    fun importRegistry(name: String, csv: String) = viewModelScope.launch {
        val r = RegistryCsvParser.parse(csv, name, System.currentTimeMillis())
        c.repository.upsert(r.cameras)
        local.update { it.copy(message = "Imported ${r.cameras.size} cameras" + if (r.errors.isNotEmpty()) " (${r.errors.size} rows skipped)" else "") }
    }

    // ------------------------------------------------------------ local network

    fun refreshNetwork() { local.update { it.copy(network = c.networkInfo.current()) } }

    fun authorizeCurrentNetwork(label: String) {
        val n = local.value.network ?: return
        viewModelScope.launch {
            c.repository.authorize(AuthorizedNetwork(NetworkGuard.authorizationIdFor(n), label.ifBlank { n.ssid ?: "Wired network" },
                n.ssid, n.bssid, n.subnet.toString(), System.currentTimeMillis()))
        }
    }

    fun revoke(id: String) = viewModelScope.launch { c.repository.revoke(id) }

    fun startDiscovery() {
        if (scanJob?.isActive == true) return
        val decision = state.value.guard
        if (decision !is GuardDecision.Allowed) { local.update { it.copy(message = (decision as GuardDecision.Denied).reason) }; return }
        scanJob = viewModelScope.launch {
            local.update { it.copy(scan = ScanState.Running("Starting")) }
            try {
                c.networkInfo.withMulticastLock {
                    c.engine.discover(decision, local.value.fix).collect { e ->
                        when (e) {
                            is DiscoveryEvent.Phase -> local.update { it.copy(scan = ScanState.Running(e.name)) }
                            is DiscoveryEvent.Progress -> local.update { s ->
                                s.copy(scan = ScanState.Running((s.scan as? ScanState.Running)?.phase ?: "", e.scanned, e.total))
                            }
                            is DiscoveryEvent.Found -> { evidence[e.camera.id] = e.evidence; c.repository.upsertLocal(e.camera, e.endpoints) }
                            is DiscoveryEvent.Denied -> local.update { it.copy(scan = ScanState.Done(e.reason)) }
                            is DiscoveryEvent.Finished -> local.update {
                                it.copy(scan = ScanState.Done("Found ${e.cameraCount} camera(s) among ${e.hostsResponding} responding host(s)"))
                            }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                local.update { it.copy(scan = ScanState.Done("Discovery stopped")) }; throw e
            } catch (e: Exception) {
                local.update { it.copy(scan = ScanState.Done("Discovery failed: ${e.message}")) }
            }
        }
    }

    fun stopDiscovery() { scanJob?.cancel(); scanJob = null; local.update { it.copy(scan = ScanState.Done("Discovery stopped")) } }

    /** One check with credentials the user typed for their own device. Not stored. */
    fun verifyCredentials(cameraId: String, username: String, password: String) = viewModelScope.launch {
        val ep = c.repository.endpointsFor(cameraId) ?: return@launch
        val a = c.engine.verifyCredentials(state.value.guard, ep, Credentials(username, password), evidence[cameraId].orEmpty())
        c.repository.updateAuth(cameraId, a)
        local.update { it.copy(message = a.classification.label) }
    }

    // ------------------------------------------------------------ UI bits

    fun select(id: String?) = local.update { it.copy(selectedId = id) }
    fun toggleCategory(cat: CameraCategory) = local.update { s ->
        val set = s.filters.categories; s.copy(filters = s.filters.copy(categories = if (cat in set) set - cat else set + cat))
    }
    fun toggleSource(g: SourceGroup) = local.update { s ->
        val set = s.filters.sources; s.copy(filters = s.filters.copy(sources = if (g in set) set - g else set + g))
    }
    fun clearInventory() = viewModelScope.launch { c.repository.clearAll(); evidence.clear() }
    fun consumeMessage() = local.update { it.copy(message = null) }

    override fun onCleared() { scanJob?.cancel() }
}
