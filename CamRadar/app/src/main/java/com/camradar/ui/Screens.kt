package com.camradar.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.camradar.core.discovery.GuardDecision
import com.camradar.core.geo.Geo
import com.camradar.core.service.GeoDiscoveryService
import com.camradar.core.service.NearbyCamera
import java.text.DateFormat
import java.util.Date

// ------------------------------------------------------------------ list

@Composable
fun CameraListScreen(s: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    if (s.visible.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No cameras yet.\nTurn on the public registry in Settings, import a registry CSV, or scan an authorized local network.")
        }
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("camera_list"), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        items(s.visible, key = { it.camera.id }) { n -> CameraRow(n) { vm.select(n.camera.id) } }
    }
}

@Composable
private fun CameraRow(n: NearbyCamera, onClick: () -> Unit) {
    val c = n.camera
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("camera_${c.id}")) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(Color(authColor(c.auth.classification)), CircleShape))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(c.name, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(c.manufacturer, c.model, c.ipAddress).joinToString(" · ").ifEmpty { c.category.label }, style = MaterialTheme.typography.bodySmall)
                Text("${c.source.label} · ${c.locationConfidence.short()}", style = MaterialTheme.typography.labelSmall)
            }
            Text(n.distanceMeters?.let { (if (GeoDiscoveryService.isApproximate(c)) "≈" else "") + Geo.formatDistance(it) } ?: "LAN",
                style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ------------------------------------------------------------------ detail panel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailSheet(n: NearbyCamera, s: UiState, vm: MainViewModel) {
    val c = n.camera
    var askCreds by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { vm.select(null) }, modifier = Modifier.testTag("detail_sheet")) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleLarge)
            Field("Manufacturer", c.manufacturer ?: "Unknown")
            Field("Model", c.model ?: "Unknown")
            Field("IP address", c.ipAddress ?: "Not available (geographic record)")
            Field("Location", c.location?.let { "%.6f, %.6f".format(it.lat, it.lon) } ?: "Not verified")
            Field("Location confidence", c.locationConfidence.label)
            Field("Distance", n.distanceMeters?.let { (if (GeoDiscoveryService.isApproximate(c)) "≈ " else "") + Geo.formatDistance(it) } ?: "—")
            Field("Category", c.category.label)
            Field("Protocols", c.protocols.joinToString().ifEmpty { "None detected" })
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(Color(authColor(c.auth.classification)), CircleShape))
                Text("  ${c.auth.classification.label}", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("auth_label"))
            }
            Field("Checked surface", c.auth.scope.label)
            Text(c.auth.rationale, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Field("Discovery source", c.source.label)
            c.sourceDetail?.let { Field("Source detail", it) }
            Field("Last seen", DateFormat.getDateTimeInstance().format(Date(c.lastSeenEpochMs)))
            if (c.networkId != null && s.guard is GuardDecision.Allowed && (s.guard as GuardDecision.Allowed).network.id == c.networkId) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { askCreds = true }) { Text("Verify with my credentials") }
                Text("For your own device. One check is sent with what you type; nothing is stored.", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (askCreds) CredentialDialog(onDismiss = { askCreds = false }) { u, p -> askCreds = false; vm.verifyCredentials(c.id, u, p) }
}

@Composable
private fun Field(label: String, value: String) = Row {
    Text("$label: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    Text(value, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CredentialDialog(onDismiss: () -> Unit, onSubmit: (String, String) -> Unit) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Your device credentials") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            }
        },
        confirmButton = { TextButton(enabled = user.isNotBlank(), onClick = { onSubmit(user, pass) }) { Text("Check once") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ local network

@Composable
fun NetworkScreen(s: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    var confirm by remember { mutableStateOf(false) }
    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Local network discovery", style = MaterialTheme.typography.titleLarge)
        Text("Finds cameras on a network you own or are allowed to test, using ONVIF, UPnP/SSDP, RTSP and HTTP identification. " +
            "It never guesses passwords and never opens video.", style = MaterialTheme.typography.bodySmall)
        Card {
            Column(Modifier.padding(12.dp)) {
                val n = s.network
                Text(if (n == null) "Not connected" else "${n.transport} · ${n.ssid ?: "no SSID"} · ${n.localAddress}/${n.prefixLength}")
                when (val g = s.guard) {
                    is GuardDecision.Allowed -> Text("Authorized as “${g.network.label}” — scanning ${g.subnet}", color = MaterialTheme.colorScheme.primary, modifier = Modifier.testTag("guard_allowed"))
                    is GuardDecision.Denied -> Text(g.reason, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("guard_denied"))
                }
                TextButton(onClick = vm::refreshNetwork) { Text("Refresh") }
            }
        }
        val allowed = s.guard is GuardDecision.Allowed
        val deniedOnlyForAuth = (s.guard as? GuardDecision.Denied)?.reason?.contains("has not been authorized") == true
        if (deniedOnlyForAuth) Button(onClick = { confirm = true }, modifier = Modifier.testTag("authorize")) { Text("Authorize this network…") }

        val scan = s.scan
        if (scan is ScanState.Running) {
            Text("${scan.phase}${if (scan.total > 0) " — ${scan.scanned}/${scan.total} hosts" else ""}")
            LinearProgressIndicator(progress = { if (scan.total > 0) scan.scanned / scan.total.toFloat() else 0f }, modifier = Modifier.fillMaxWidth())
            Button(onClick = vm::stopDiscovery, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("stop_discovery")) { Text("Stop discovery") }
        } else {
            Button(onClick = vm::startDiscovery, enabled = allowed, modifier = Modifier.fillMaxWidth().testTag("start_discovery")) { Text("Start discovery") }
            if (scan is ScanState.Done) Text(scan.message, modifier = Modifier.testTag("scan_result"))
        }

        if (s.authorized.isNotEmpty()) {
            Text("Authorized networks", style = MaterialTheme.typography.titleMedium)
            s.authorized.forEach { a ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(a.label); Text("${a.ssid ?: "wired"} · ${a.subnetCidr}", style = MaterialTheme.typography.labelSmall) }
                    TextButton(onClick = { vm.revoke(a.id) }) { Text("Revoke") }
                }
            }
        }
    }
    if (confirm) AuthorizeDialog(s.network?.ssid ?: "this network", onDismiss = { confirm = false }) { label -> confirm = false; vm.authorizeCurrentNetwork(label) }
}

@Composable
private fun AuthorizeDialog(name: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var owns by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Authorize $name?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("CamRadar will send discovery and identification requests to devices on this network's subnet. Scanning networks you don't control may be illegal.")
                OutlinedTextField(label, { label = it }, label = { Text("Label") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(owns, { owns = it }, modifier = Modifier.testTag("owns_checkbox"))
                    Text("I own this network or have explicit permission to scan it.")
                }
            }
        },
        confirmButton = { TextButton(enabled = owns, onClick = { onConfirm(label) }, modifier = Modifier.testTag("confirm_authorize")) { Text("Authorize") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ------------------------------------------------------------------ settings / privacy

@Composable
fun SettingsScreen(s: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: return@rememberLauncherForActivityResult
        vm.importRegistry(uri.lastPathSegment?.substringAfterLast('/') ?: "registry", text)
    }
    Column(modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings & privacy", style = MaterialTheme.typography.titleLarge)
        ToggleRow("Public camera registry (OpenStreetMap)",
            "Sends the search area to the Overpass API to fetch cameras mapped by OpenStreetMap contributors. Requires internet.",
            s.settings.publicRegistryConsent, "registry_toggle", vm::setRegistryConsent)
        ToggleRow("Simulated GPS", "Replays a scripted walk for emulator testing. Locations are marked SIMULATED.",
            s.settings.simulatedGps, "simgps_toggle", vm::setSimulatedGps)
        OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "text/csv", "text/comma-separated-values")) }) { Text("Import camera registry (CSV)…") }
        Text("CSV columns: id,name,lat,lon[,manufacturer,model,category,precision]", style = MaterialTheme.typography.labelSmall)
        OutlinedButton(onClick = vm::clearInventory) { Text("Delete all stored cameras") }
        HorizontalDivider()
        Text("Location is read only while CamRadar is on screen. Your camera inventory stays on this phone; it is never uploaded and is excluded from backups. " +
            "CamRadar never opens camera video and never tries passwords.", style = MaterialTheme.typography.bodySmall)
        Text("Map data © OpenStreetMap contributors (ODbL).", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ToggleRow(title: String, body: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
    Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(body, style = MaterialTheme.typography.bodySmall) }
    Switch(on, onChange, modifier = Modifier.testTag(tag))
}
