package com.camradar

import com.camradar.core.discovery.Endpoints
import com.camradar.core.geo.LatLon
import com.camradar.core.model.AuthAssessment
import com.camradar.core.model.AuthClassification
import com.camradar.core.model.AuthScope
import com.camradar.core.model.Camera
import com.camradar.core.model.CameraCategory
import com.camradar.core.model.DiscoverySource
import com.camradar.core.model.LocationConfidence
import com.camradar.core.model.Protocol
import com.camradar.data.CameraEntity
import com.camradar.ui.SourceGroup
import com.camradar.ui.group
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraEntityMappingTest {
    private val cam = Camera(
        "lan:n:192.168.1.64", "Garage", "Hikvision", "DS-2CD", "192.168.1.64", LatLon(42.7, 23.3),
        LocationConfidence.USER_LOCATION_AT_DISCOVERY, CameraCategory.LOCAL_NETWORK, setOf(Protocol.RTSP, Protocol.ONVIF),
        AuthAssessment(AuthClassification.AUTH_REQUIRED, AuthScope.VIDEO_STREAM, "401"), DiscoverySource.LOCAL_ONVIF, "detail", "n", 99L,
    )

    @Test fun roundTrip() {
        val e = CameraEntity.from(cam, Endpoints("192.168.1.64", 554, 80, "http://192.168.1.64/onvif/device_service"))
        assertEquals(cam, e.toModel())
        assertEquals(554, e.endpoints()!!.rtspPort)
    }

    @Test fun unknownEnumValuesFallBack() {
        val e = CameraEntity.from(cam).copy(authClassification = "LEGACY", protocols = "RTSP,BOGUS")
        assertEquals(AuthClassification.UNKNOWN, e.toModel().auth.classification)
        assertEquals(setOf(Protocol.RTSP), e.toModel().protocols)
    }

    @Test fun sourceGroups() {
        assertEquals(SourceGroup.PUBLIC_REGISTRY, DiscoverySource.OSM_SURVEILLANCE.group())
        assertEquals(SourceGroup.LOCAL_NETWORK, DiscoverySource.LOCAL_SSDP.group())
        assertEquals(SourceGroup.IMPORTED, DiscoverySource.IMPORTED_REGISTRY.group())
    }
}
