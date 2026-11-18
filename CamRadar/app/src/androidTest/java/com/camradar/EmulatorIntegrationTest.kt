package com.camradar

import android.Manifest
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.camradar.core.discovery.Datagram
import com.camradar.core.discovery.NetTransport
import com.camradar.core.geo.LatLon
import com.camradar.core.geo.SimulatedLocationSource
import com.camradar.core.model.Camera
import com.camradar.core.model.CameraCategory
import com.camradar.core.model.DiscoverySource
import com.camradar.core.model.LocationConfidence
import com.camradar.di.AppContainer
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/**
 * End-to-end on an emulator with: simulated GPS, a fake public registry, an in-memory DB and a
 * network transport that refuses all traffic. No real cameras, no real network scanning.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorIntegrationTest {
    @get:Rule val perms: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION)
    @get:Rule val compose = createEmptyComposeRule()

    private val here = LatLon(42.6977, 23.3219)
    private var online = true

    private val deadNetwork = object : NetTransport {
        override suspend fun multicast(group: String, port: Int, payload: String, listenMs: Long) = emptyList<Datagram>()
        override suspend fun isPortOpen(host: String, port: Int, timeoutMs: Int) = false
        override suspend fun exchange(host: String, port: Int, request: String, timeoutMs: Int): String? = null
    }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<CamRadarApp>()
        val c = AppContainer(app, AppContainer.Overrides(
            inMemoryDb = true, transport = deadNetwork,
            location = SimulatedLocationSource(listOf(here), listOf(7f)),
            remoteGeo = { center, _ ->
                if (!online) throw IOException("offline")
                listOf(Camera("osm:node/1", "Test Square Camera", location = LatLon(center.lat + 0.001, center.lon),
                    locationConfidence = LocationConfidence.EXACT_REGISTERED, category = CameraCategory.PUBLIC_SURVEILLANCE,
                    source = DiscoverySource.OSM_SURVEILLANCE, lastSeenEpochMs = 0))
            },
        ))
        c.settings.update { it.copy(publicRegistryConsent = true, simulatedGps = false) }
        app.replaceContainer(c)
    }

    @Test fun showsSimulatedFixAccuracyAndRegistryCamera() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("accuracy_label")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("accuracy_label").assertTextContains("±7 m", substring = true); true }.getOrDefault(false) }
            compose.onNodeWithTag("accuracy_label").assertTextContains("SIMULATED", substring = true)
            compose.onNodeWithTag("tab_LIST").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("camera_osm:node/1")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("camera_osm:node/1").performClick()
            compose.onNodeWithText("Exact registered coordinates", substring = true).assertExists()
        }
    }

    @Test fun radiusChangeFiltersOutCamera() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("radius_M100")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("radius_M100").performClick() // camera is ~111 m away
            compose.onNodeWithTag("tab_LIST").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("camera_osm:node/1")).fetchSemanticsNodes().isEmpty() }
        }
    }

    @Test fun offlineModeKeepsWorking() {
        online = false
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(5_000) { runCatching { compose.onNodeWithText("cached results", substring = true).assertExists(); true }.getOrDefault(false) }
        }
    }

    @Test fun networkTabRefusesUnauthorizedNetwork() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("tab_NETWORK").performClick()
            // Emulator Wi-Fi is not authorized in a fresh in-memory DB, so discovery must be blocked.
            compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("guard_denied")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("start_discovery").assertExists()
        }
    }
}
