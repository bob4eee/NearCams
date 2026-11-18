package com.camradar.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import com.camradar.core.discovery.AuthorizedNetwork
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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cameras")
data class CameraEntity(
    @PrimaryKey val id: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val ipAddress: String?,
    val lat: Double?,
    val lon: Double?,
    val locationConfidence: String,
    val category: String,
    val protocols: String,
    val authClassification: String,
    val authScope: String,
    val authRationale: String,
    val source: String,
    val sourceDetail: String?,
    val networkId: String?,
    val lastSeenEpochMs: Long,
    val rtspPort: Int? = null,
    val httpPort: Int? = null,
    val onvifXAddr: String? = null,
) {
    fun toModel() = Camera(
        id, name, manufacturer, model, ipAddress,
        if (lat != null && lon != null) LatLon(lat, lon) else null,
        enumOr(locationConfidence, LocationConfidence.UNKNOWN), enumOr(category, CameraCategory.OTHER),
        protocols.split(',').filter { it.isNotBlank() }.mapNotNull { p -> Protocol.entries.firstOrNull { it.name == p } }.toSet(),
        AuthAssessment(enumOr(authClassification, AuthClassification.UNKNOWN), enumOr(authScope, AuthScope.NONE), authRationale),
        enumOr(source, DiscoverySource.MANUAL), sourceDetail, networkId, lastSeenEpochMs,
    )

    fun endpoints(): Endpoints? = ipAddress?.let { Endpoints(it, rtspPort, httpPort, onvifXAddr) }

    companion object {
        fun from(c: Camera, e: Endpoints? = null) = CameraEntity(
            c.id, c.name, c.manufacturer, c.model, c.ipAddress, c.location?.lat, c.location?.lon,
            c.locationConfidence.name, c.category.name, c.protocols.joinToString(",") { it.name },
            c.auth.classification.name, c.auth.scope.name, c.auth.rationale, c.source.name, c.sourceDetail,
            c.networkId, c.lastSeenEpochMs, e?.rtspPort, e?.httpPort, e?.onvifXAddr,
        )

        private inline fun <reified T : Enum<T>> enumOr(v: String, d: T): T = enumValues<T>().firstOrNull { it.name == v } ?: d
    }
}

@Entity(tableName = "authorized_networks")
data class AuthorizedNetworkEntity(
    @PrimaryKey val id: String,
    val label: String,
    val ssid: String?,
    val bssid: String?,
    val subnetCidr: String,
    val authorizedAtEpochMs: Long,
) {
    fun toModel() = AuthorizedNetwork(id, label, ssid, bssid, subnetCidr, authorizedAtEpochMs)
    companion object { fun from(a: AuthorizedNetwork) = AuthorizedNetworkEntity(a.id, a.label, a.ssid, a.bssid, a.subnetCidr, a.authorizedAtEpochMs) }
}

@Dao
interface CameraDao {
    @Query("SELECT * FROM cameras ORDER BY lastSeenEpochMs DESC") fun observeAll(): Flow<List<CameraEntity>>
    @Query("SELECT * FROM cameras") suspend fun all(): List<CameraEntity>
    @Query("SELECT * FROM cameras WHERE id = :id") suspend fun byId(id: String): CameraEntity?
    @Upsert suspend fun upsert(items: List<CameraEntity>)
    @Query("DELETE FROM cameras WHERE networkId = :networkId") suspend fun deleteForNetwork(networkId: String)
    @Query("DELETE FROM cameras") suspend fun clear()
    @Query("UPDATE cameras SET authClassification = :c, authScope = :s, authRationale = :r WHERE id = :id")
    suspend fun updateAuth(id: String, c: String, s: String, r: String)
}

@Dao
interface AuthorizedNetworkDao {
    @Query("SELECT * FROM authorized_networks ORDER BY authorizedAtEpochMs DESC") fun observeAll(): Flow<List<AuthorizedNetworkEntity>>
    @Query("SELECT * FROM authorized_networks") suspend fun all(): List<AuthorizedNetworkEntity>
    @Upsert suspend fun upsert(item: AuthorizedNetworkEntity)
    @Query("DELETE FROM authorized_networks WHERE id = :id") suspend fun delete(id: String)
}

@Database(entities = [CameraEntity::class, AuthorizedNetworkEntity::class], version = 1, exportSchema = false)
abstract class CamRadarDatabase : RoomDatabase() {
    abstract fun cameras(): CameraDao
    abstract fun networks(): AuthorizedNetworkDao

    companion object {
        fun build(context: Context, inMemory: Boolean = false): CamRadarDatabase =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, CamRadarDatabase::class.java)
            else Room.databaseBuilder(context, CamRadarDatabase::class.java, "camradar.db")).build()
    }
}
