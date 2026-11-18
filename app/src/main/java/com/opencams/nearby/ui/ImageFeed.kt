package com.opencams.nearby.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** Refreshing still-image feed (snapshot cams). Re-requests every [intervalMs]. */
@Composable
fun ImageFeed(url: String, modifier: Modifier = Modifier, intervalMs: Long = 2000L) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(url) {
        while (true) {
            delay(intervalMs)
            tick++
        }
    }
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(url)
            .setParameter("tick", tick, memoryCacheKey = null)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier = modifier.fillMaxWidth()
    )
}
