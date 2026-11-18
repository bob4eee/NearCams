package com.camradar.data

import com.camradar.core.discovery.AuthorizedNetwork
import com.camradar.core.discovery.Endpoints
import com.camradar.core.model.AuthAssessment
import com.camradar.core.model.Camera
import com.camradar.core.service.CameraStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Single source of truth for the on-device camera inventory. Nothing here is ever uploaded. */
class CameraRepository(private val db: CamRadarDatabase) : CameraStore {
    val cameras: Flow<List<Camera>> = db.cameras().observeAll().map { list -> list.map { it.toModel() } }
    val authorizedNetworks: Flow<List<AuthorizedNetwork>> = db.networks().observeAll().map { l -> l.map { it.toModel() } }

    override suspend fun upsert(cameras: List<Camera>) = db.cameras().upsert(cameras.map { CameraEntity.from(it) })
    override suspend fun all(): List<Camera> = db.cameras().all().map { it.toModel() }

    suspend fun upsertLocal(camera: Camera, endpoints: Endpoints) = db.cameras().upsert(listOf(CameraEntity.from(camera, endpoints)))
    suspend fun endpointsFor(id: String): Endpoints? = db.cameras().byId(id)?.endpoints()
    suspend fun updateAuth(id: String, a: AuthAssessment) = db.cameras().updateAuth(id, a.classification.name, a.scope.name, a.rationale)
    suspend fun clearAll() = db.cameras().clear()

    suspend fun authorizedList() = db.networks().all().map { it.toModel() }
    suspend fun authorize(n: AuthorizedNetwork) = db.networks().upsert(AuthorizedNetworkEntity.from(n))
    suspend fun revoke(id: String) { db.networks().delete(id); db.cameras().deleteForNetwork(id) }
}
