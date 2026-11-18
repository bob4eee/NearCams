package com.camradar.core

import com.camradar.core.geo.AccuracyLevel
import com.camradar.core.geo.Geo
import com.camradar.core.geo.LatLon
import com.camradar.core.geo.SearchRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val sofia = LatLon(42.6977, 23.3219)

    @Test fun distanceSofiaToPlovdivIsAbout132km() {
        val d = Geo.distanceMeters(sofia, LatLon(42.1354, 24.7453))
        assertEquals(132_500.0, d, 2_000.0)
    }

    @Test fun zeroDistanceToSelf() = assertEquals(0.0, Geo.distanceMeters(sofia, sofia), 1e-6)

    @Test fun radiiMatchSpec() =
        assertEquals(listOf(100, 250, 500, 1000, 5000), SearchRadius.entries.map { it.meters })

    @Test fun boundingBoxContainsRadius() {
        SearchRadius.entries.forEach { r ->
            val bb = Geo.boundingBox(sofia, r.meters)
            assertEquals(r.meters.toDouble(), Geo.distanceMeters(sofia, LatLon(bb.north, sofia.lon)), r.meters * 0.01)
        }
    }

    @Test fun withinRadius() {
        val p = LatLon(42.6977 + 0.002, 23.3219) // ~222 m north
        assertTrue(Geo.isWithin(sofia, p, 250)); assertFalse(Geo.isWithin(sofia, p, 100))
    }

    @Test fun accuracyLevels() {
        assertEquals(AccuracyLevel.HIGH, AccuracyLevel.fromMeters(5f))
        assertEquals(AccuracyLevel.MEDIUM, AccuracyLevel.fromMeters(30f))
        assertEquals(AccuracyLevel.LOW, AccuracyLevel.fromMeters(150f))
        assertEquals(AccuracyLevel.POOR, AccuracyLevel.fromMeters(900f))
    }

    @Test fun formatDistance() {
        assertEquals("87 m", Geo.formatDistance(87.4)); assertEquals("2.3 km", Geo.formatDistance(2_340.0))
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsBadLatitude() { LatLon(91.0, 0.0) }
}
