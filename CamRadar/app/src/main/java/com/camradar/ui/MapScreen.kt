package com.camradar.ui

import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.camradar.core.geo.Geo
import com.camradar.core.geo.SearchRadius
import com.camradar.core.model.AuthClassification
import com.camradar.core.model.LocationConfidence
import com.camradar.core.service.GeoDiscoveryService
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

fun authColor(a: AuthClassification) = when (a) {
    AuthClassification.AUTH_REQUIRED -> 0xFF2E7D32.toInt()
    AuthClassification.VERIFIED_WITH_USER_CREDENTIALS -> 0xFF1565C0.toInt()
    AuthClassification.NO_AUTH_DETECTED -> 0xFFC62828.toInt()
    AuthClassification.UNKNOWN -> 0xFF616161.toInt()
}

/** Solid dot = exact registered coordinates. Hollow ring = approximate (registry centroid, phone position, or IP geolocation). */
private fun markerIcon(color: Int, approximate: Boolean, selected: Boolean) = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    val px = if (selected) 56 else 40
    setSize(px, px)
    if (approximate) { setColor(AColor.argb(60, AColor.red(color), AColor.green(color), AColor.blue(color))); setStroke(6, color, 10f, 6f) }
    else { setColor(color); setStroke(4, AColor.WHITE) }
}

@Composable
fun MapScreen(s: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val map = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.0)
            controller.setCenter(GeoPoint(42.6977, 23.3219))
        }
    }
    DisposableEffect(Unit) {
        map.onResume()
        map.addMapListener(object : MapListener {
            override fun onScroll(e: ScrollEvent?): Boolean { map.mapCenter.let { vm.setManualCenter(com.camradar.core.geo.LatLon(it.latitude, it.longitude)) }; return false }
            override fun onZoom(e: ZoomEvent?) = false
        })
        onDispose { map.onPause() }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(factory = { map }, modifier = Modifier.fillMaxSize().testTag("map"), update = { mv -> renderOverlays(mv, s, vm) })

        Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 3.dp, shadowElevation = 3.dp) {
                Column(Modifier.padding(10.dp)) {
                    val fix = s.fix
                    Text(
                        when {
                            fix != null -> "GPS ±${fix.accuracyMeters.toInt()} m · ${fix.accuracyLevel.label}" + if (fix.isMock) " · SIMULATED" else ""
                            s.hasLocationPermission || s.settings.simulatedGps -> "Waiting for GPS fix…"
                            else -> "No location — searching around the map centre"
                        },
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("accuracy_label"),
                    )
                    Text("${s.visible.size} cameras within ${s.settings.radius.label}" + (s.geoStatus?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall)
                    if (s.geoLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SearchRadius.entries.forEach { r ->
                    FilterChip(selected = s.settings.radius == r, onClick = { vm.setRadius(r) }, label = { Text(r.label) }, modifier = Modifier.testTag("radius_${r.name}"))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceGroup.entries.forEach { g -> FilterChip(selected = g in s.filters.sources, onClick = { vm.toggleSource(g) }, label = { Text(g.label) }) }
                com.camradar.core.model.CameraCategory.entries.forEach { c ->
                    FilterChip(selected = c in s.filters.categories, onClick = { vm.toggleCategory(c) }, label = { Text(c.label) })
                }
            }
        }
        Legend(Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}

private fun renderOverlays(mv: MapView, s: UiState, vm: MainViewModel) {
    mv.overlays.clear()
    val center = s.center
    if (center != null) {
        val gp = GeoPoint(center.lat, center.lon)
        mv.overlays += Polygon(mv).apply {
            points = Polygon.pointsAsCircle(gp, s.settings.radius.meters.toDouble())
            fillPaint.color = AColor.argb(25, 15, 110, 94); outlinePaint.color = AColor.argb(160, 15, 110, 94); outlinePaint.strokeWidth = 3f
            setOnClickListener { _, _, _ -> false }
        }
        s.fix?.let { f ->
            mv.overlays += Polygon(mv).apply {
                points = Polygon.pointsAsCircle(gp, f.accuracyMeters.toDouble())
                fillPaint.color = AColor.argb(50, 33, 150, 243); outlinePaint.color = AColor.argb(120, 33, 150, 243); outlinePaint.strokeWidth = 2f
                setOnClickListener { _, _, _ -> false }
            }
            mv.overlays += Marker(mv).apply {
                position = gp; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER); title = "You (±${f.accuracyMeters.toInt()} m)"
                icon = GradientDrawable().apply { shape = GradientDrawable.OVAL; setSize(34, 34); setColor(0xFF2196F3.toInt()); setStroke(6, AColor.WHITE) }
            }
            if (mv.tag != "centered") { mv.controller.setCenter(gp); mv.tag = "centered" }
        }
    }
    s.visible.forEach { n ->
        val loc = n.camera.location ?: return@forEach
        mv.overlays += Marker(mv).apply {
            position = GeoPoint(loc.lat, loc.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = markerIcon(authColor(n.camera.auth.classification), GeoDiscoveryService.isApproximate(n.camera), n.camera.id == s.selectedId)
            title = n.camera.name
            snippet = n.distanceMeters?.let { d -> (if (GeoDiscoveryService.isApproximate(n.camera)) "≈ " else "") + Geo.formatDistance(d) }
            setOnMarkerClickListener { _, _ -> vm.select(n.camera.id); true }
        }
    }
    mv.overlays += CopyrightOverlay(mv.context)
    mv.invalidate()
}

@Composable
private fun Legend(modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), tonalElevation = 3.dp) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LegendRow(Color(authColor(AuthClassification.AUTH_REQUIRED)), "Auth required")
            LegendRow(Color(authColor(AuthClassification.NO_AUTH_DETECTED)), "No auth detected")
            LegendRow(Color(authColor(AuthClassification.VERIFIED_WITH_USER_CREDENTIALS)), "Verified (your login)")
            LegendRow(Color(authColor(AuthClassification.UNKNOWN)), "Unknown")
            Text("● exact position   ◌ approximate", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LegendRow(c: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(10.dp).background(c, CircleShape)); Text("  $label", style = MaterialTheme.typography.labelSmall)
}

fun LocationConfidence.short() = when (this) {
    LocationConfidence.EXACT_REGISTERED -> "Exact"
    LocationConfidence.APPROXIMATE_REGISTERED -> "Approx. (registry)"
    LocationConfidence.USER_LOCATION_AT_DISCOVERY -> "Approx. (your position)"
    LocationConfidence.APPROXIMATE_IP_GEOLOCATION -> "Approx. (IP geolocation)"
    LocationConfidence.UNKNOWN -> "Unknown"
}
