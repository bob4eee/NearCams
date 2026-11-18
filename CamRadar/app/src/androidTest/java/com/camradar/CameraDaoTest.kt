package com.camradar

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.camradar.core.discovery.AuthorizedNetwork
import com.camradar.core.model.AuthAssessment
import com.camradar.core.model.AuthClassification
import com.camradar.core.model.AuthScope
import com.camradar.core.model.Camera
import com.camradar.core.model.DiscoverySource
import com.camradar.data.CamRadarDatabase
import com.camradar.data.CameraRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraDaoTest {
    private val db = CamRadarDatabase.build(ApplicationProvider.getApplicationContext(), inMemory = true)
    private val repo = CameraRepository(db)

    @After fun close() = db.close()

    private fun cam(id: String, net: String? = null) = Camera(id, id, networkId = net, source = DiscoverySource.LOCAL_ONVIF, lastSeenEpochMs = 1)

    @Test fun upsertObserveAndUpdateAuth() = runTest {
        repo.upsert(listOf(cam("a"), cam("b")))
        repo.upsert(listOf(cam("a").copy(name = "renamed")))
        val all = repo.cameras.first()
        assertEquals(2, all.size); assertEquals("renamed", all.first { it.id == "a" }.name)
        repo.updateAuth("a", AuthAssessment(AuthClassification.VERIFIED_WITH_USER_CREDENTIALS, AuthScope.VIDEO_STREAM, "ok"))
        assertEquals(AuthClassification.VERIFIED_WITH_USER_CREDENTIALS, repo.all().first { it.id == "a" }.auth.classification)
    }

    @Test fun revokingNetworkDeletesItsCameras() = runTest {
        repo.authorize(AuthorizedNetwork("n1", "Home", "Home", null, "192.168.1.0/24", 0))
        repo.upsert(listOf(cam("x", "n1"), cam("y", "n2"), cam("z")))
        repo.revoke("n1")
        assertEquals(setOf("y", "z"), repo.all().map { it.id }.toSet())
        assertEquals(0, repo.authorizedList().size)
    }
}
