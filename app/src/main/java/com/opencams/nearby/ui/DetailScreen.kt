package com.opencams.nearby.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.opencams.nearby.data.Camera
import com.opencams.nearby.data.FeedType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(cam: Camera, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(cam.title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp)) {
            when (cam.feedType) {
                FeedType.STREAM -> VideoPlayer(
                    url = cam.url,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                )
                FeedType.IMAGE -> ImageFeed(
                    url = cam.url,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text(cam.location, Modifier.padding(top = 12.dp), fontWeight = FontWeight.SemiBold)
            cam.distanceKm?.let { Text("%.0f km away".format(it), fontSize = 13.sp) }
            cam.source?.let {
                Text("Source: $it", Modifier.padding(top = 4.dp), fontSize = 12.sp)
            }

            if (cam.hasControls && cam.ptzBaseUrl != null) {
                PtzControls(cam.ptzBaseUrl)
            } else {
                Text(
                    "View-only feed — this operator does not expose public controls.",
                    Modifier.padding(top = 16.dp),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PtzControls(ptzBaseUrl: String) {
    val controller = PtzController(ptzBaseUrl)
    val scope = rememberCoroutineScope()
    fun move(m: PtzController.Move) = scope.launch { controller.send(m) }

    Column(Modifier.padding(top = 16.dp)) {
        Text("Controls", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { move(PtzController.Move.UP) }) {
                Icon(Icons.Filled.KeyboardArrowUp, "Up")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment_CenterHorizontally),
            modifier = Modifier.fillMaxWidth()
        ) {
            IconButton(onClick = { move(PtzController.Move.LEFT) }) {
                Icon(Icons.Filled.KeyboardArrowLeft, "Left")
            }
            OutlinedButton(onClick = { move(PtzController.Move.STOP) }) { Text("Stop") }
            IconButton(onClick = { move(PtzController.Move.RIGHT) }) {
                Icon(Icons.Filled.KeyboardArrowRight, "Right")
            }
        }
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = { move(PtzController.Move.DOWN) }) {
                Icon(Icons.Filled.KeyboardArrowDown, "Down")
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Button(onClick = { move(PtzController.Move.ZOOM_IN) }) { Text("Zoom +") }
            Button(onClick = { move(PtzController.Move.ZOOM_OUT) }) { Text("Zoom −") }
        }
    }
}

private val Alignment_CenterHorizontally = androidx.compose.ui.Alignment.CenterHorizontally
