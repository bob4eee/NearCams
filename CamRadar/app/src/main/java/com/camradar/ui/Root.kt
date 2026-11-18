package com.camradar.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class Tab(val label: String) { MAP("Map"), LIST("Cameras"), NETWORK("Local network"), SETTINGS("Settings") }

@Composable
fun CamRadarTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) darkColorScheme(primary = Color(0xFF7FE0C3), secondary = Color(0xFFFFB86B))
        else lightColorScheme(primary = Color(0xFF0F6E5E), secondary = Color(0xFFB4591A)),
        content = content,
    )
}

@Composable
fun CamRadarRoot(vm: MainViewModel) = CamRadarTheme {
    val s by vm.state.collectAsStateWithLifecycle()
    var onboarded by rememberSaveable { mutableStateOf(s.hasLocationPermission) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        val granted = r.values.any { it }
        vm.onPermissionResult(granted); onboarded = true
        if (granted) vm.startLocation()
    }

    if (!onboarded && !s.hasLocationPermission) {
        PermissionRationale(
            onGrant = { launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
            onSkip = { onboarded = true },
        )
        return@CamRadarTheme
    }

    var tab by rememberSaveable { mutableStateOf(Tab.MAP) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.message) { s.message?.let { snack.showSnackbar(it); vm.consumeMessage() } }

    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t; if (t == Tab.NETWORK) vm.refreshNetwork() },
                        icon = { Icon(when (t) { Tab.MAP -> Icons.Default.Map; Tab.LIST -> Icons.Default.List; Tab.NETWORK -> Icons.Default.Wifi; Tab.SETTINGS -> Icons.Default.Settings }, null) },
                        label = { Text(t.label) }, modifier = Modifier.testTag("tab_${t.name}"))
                }
            }
        },
    ) { pad ->
        val m = Modifier.padding(pad)
        when (tab) {
            Tab.MAP -> MapScreen(s, vm, m)
            Tab.LIST -> CameraListScreen(s, vm, m)
            Tab.NETWORK -> NetworkScreen(s, vm, m)
            Tab.SETTINGS -> SettingsScreen(s, vm, m)
        }
    }
    s.selected?.let { DetailSheet(it, s, vm) }
}

@Composable
fun PermissionRationale(onGrant: () -> Unit, onSkip: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp).testTag("permission_rationale"), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("CamRadar uses your location", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "Your position is used on this phone to centre the map and work out distances to registered cameras. " +
                "It's only read while the app is open — never in the background — and it isn't uploaded. " +
                "If you turn on the public registry, only the search area is sent to OpenStreetMap to look up mapped cameras.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant, modifier = Modifier.testTag("grant_location")) { Text("Allow location while using the app") }
        TextButton(onClick = onSkip, modifier = Modifier.testTag("skip_location")) { Text("Continue without location") }
    }
}
