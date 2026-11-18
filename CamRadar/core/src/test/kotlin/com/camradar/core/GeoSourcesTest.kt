package com.camradar.core

import com.camradar.core.geo.LatLon
import com.camradar.core.geo.OverpassParser
import com.camradar.core.geo.OverpassSource
import com.camradar.core.geo.RegistryCsvParser
import com.camradar.core.model.CameraCategory
import com.camradar.core.model.DiscoverySource
import com.camradar.core.model.LocationConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoSourcesTest {
    private val sample = """{"elements":[
      {"type":"node","id":1,"lat":42.70,"lon":23.32,"tags":{"man_made":"surveillance","surveillance":"public","operator":"Sofia Municipality","camera:type":"dome"}},
      {"type":"way","id":2,"center":{"lat":42.71,"lon":23.33},"tags":{"man_made":"surveillance","surveillance:type":"ALPR"}},
      {"type":"node","id":3,"tags":{"man_made":"surveillance"}},
      {"type":"node","id":4,"lat":42.69,"lon":23.31,"tags":{"surveillance":"indoor","name":"Shop cam"}}]}"""

    @Test fun parsesNodesAndWayCenters() {
        val cams = OverpassParser.parse(sample, 1000L)
        assertEquals(3, cams.size)
        val first = cams[0]
        assertEquals("osm:node/1", first.id)
        assertEquals(LocationConfidence.EXACT_REGISTERED, first.locationConfidence)
        assertEquals(CameraCategory.PUBLIC_SURVEILLANCE, first.category)
        assertEquals(DiscoverySource.OSM_SURVEILLANCE, first.source)
        assertTrue(first.sourceDetail!!.contains("operator=Sofia Municipality"))
        assertEquals(LocationConfidence.APPROXIMATE_REGISTERED, cams[1].locationConfidence)
        assertEquals(CameraCategory.TRAFFIC, cams[1].category)
        assertEquals("Shop cam", cams[2].name)
        assertEquals(CameraCategory.PRIVATE_SURVEILLANCE, cams[2].category)
    }

    @Test fun emptyResponse() = assertEquals(0, OverpassParser.parse("""{"elements":[]}""", 0).size)

    @Test fun overpassUsesFetcherAndRadius() {
        var sent = ""
        val src = OverpassSource({ _, body, _ -> sent = body; sample }, clock = { 5L })
        val cams = src.fetch(LatLon(42.7, 23.32), 500)
        assertEquals(3, cams.size)
        assertTrue(java.net.URLDecoder.decode(sent, "UTF-8").contains("around:500,42.7,23.32"))
    }

    @Test fun csvRegistry() {
        val csv = """
            # municipal export
            id,name,lat,lon,manufacturer,category,precision
            a1,"Main St, north",42.70,23.32,Axis,TRAFFIC,exact
            a2,Park,42.71,23.30,,PUBLIC_SURVEILLANCE,ip_geolocation
            a3,Broken,abc,23.3
        """.trimIndent()
        val r = RegistryCsvParser.parse(csv, "sofia-open-data", 1L)
        assertEquals(2, r.cameras.size)
        assertEquals("Main St, north", r.cameras[0].name)
        assertEquals(CameraCategory.TRAFFIC, r.cameras[0].category)
        assertEquals(LocationConfidence.APPROXIMATE_IP_GEOLOCATION, r.cameras[1].locationConfidence)
        assertEquals(1, r.errors.size)
    }

    @Test fun csvMissingColumns() =
        assertTrue(RegistryCsvParser.parse("id,name\n1,x", "r", 0).errors.first().contains("lat"))
}
