package com.opencams.nearby.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencams.nearby.data.Camera

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NearbyListScreen(
    state: UiState,
    onOpen: (Camera) -> Unit,
    onOpenMap: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Public cameras near you") },
                actions = {
                    IconButton(onClick = onOpenMap) {
                        Icon(Icons.Filled.Map, contentDescription = "Map")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            if (!state.hasWindyKey) {
                Text(
                    "Showing the bundled catalog. Add a Windy Webcams API key " +
                        "(gradle.properties) for live GPS discovery of nearby public cams.",
                    fontSize = 12.sp,
                    modifier = Modifier.padding(16.dp, 8.dp)
                )
            }
            state.error?.let {
                Text("Error: $it", Modifier.padding(16.dp, 8.dp))
            }

            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.cameras, key = { it.id }) { cam ->
                    CameraRow(cam, onOpen)
                }
            }
        }
    }
}

@Composable
private fun CameraRow(cam: Camera, onOpen: (Camera) -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable { onOpen(cam) }
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Videocam, contentDescription = null)
            Column(Modifier.padding(start = 14.dp).fillMaxWidth()) {
                Text(cam.title, fontWeight = FontWeight.SemiBold)
                Text(cam.location, fontSize = 13.sp)
                Row {
                    cam.distanceKm?.let {
                        Text("%.0f km away".format(it), fontSize = 12.sp)
                    }
                    if (cam.hasControls) {
                        Text("  •  controllable", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
