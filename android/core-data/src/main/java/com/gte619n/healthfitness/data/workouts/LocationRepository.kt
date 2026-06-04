package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.data.db.dao.LocationDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.net.BackendBaseUrl
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.workouts.CreateLocationRequest
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.domain.workouts.PendingUpload
import com.gte619n.healthfitness.domain.workouts.UpdateLocationRequest
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.flow.first
import retrofit2.Response
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 5) — Room-backed, offline-first gym/location repository.
 *
 * Reads ([list]/[get]) come from the `locations` mirror (D8); the network only
 * fills it (one-shot on a cold miss + the background SyncEngine pull). The primary
 * CRUD ([create]/[update]/[delete]) is optimistic + outbox (D7): a create mints a
 * client UUID, writes a PENDING row that appears instantly, and replays to
 * `api/me/gyms` (see [com.gte619n.healthfitness.data.sync.OutboxEndpointRegistry]).
 *
 * The sub-resource mutations ([setDefault], equipment add/remove/specs) and the
 * multipart cover-photo upload stay on the network path: the default-flip is
 * server-evaluated (exclusive across rows), equipment specs are validated
 * server-side, and the photo is a binary upload (online-only per D17). Each
 * refreshes the affected mirror row afterward so Room stays the source of truth
 * for what the screen renders.
 */
@Singleton
class LocationRepository @Inject constructor(
    private val api: LocationApi,
    private val multipart: MultipartUploadClient,
    @BackendBaseUrl private val baseUrl: String,
    private val dao: LocationDao,
    private val support: MirrorRepositorySupport,
    private val moshi: Moshi,
) {

    private val dtoAdapter = moshi.adapter(LocationDto::class.java)

    private val mapAdapter =
        moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )

    suspend fun list(includeInactive: Boolean = false): Result<List<Location>> = runCatching {
        if (support.killSwitchOn()) {
            return@runCatching api.list(if (includeInactive) "inactive" else null).map { it.toDomain() }
        }
        // Fill the mirror on a cold miss, then serve from Room.
        if (dao.observeActive().first().isEmpty()) fillMirror(includeInactive)
        dao.observeActive().first()
            .mapNotNull { decode(it.payloadJson) }
            .map { it.toDomain() }
            .filter { includeInactive || it.isActive }
    }

    suspend fun get(locationId: String): Result<Location> = runCatching {
        if (support.killSwitchOn()) return@runCatching api.get(locationId).toDomain()
        mirroredDto(locationId)?.toDomain() ?: refreshOne(locationId).toDomain()
    }

    suspend fun create(req: CreateLocationRequest): Result<Location> = runCatching {
        val id = UUID.randomUUID().toString()
        val now = Instant.now().toString()
        val dto = LocationDto(
            locationId = id,
            name = req.name,
            address = req.address,
            coverPhotoUrl = null,
            is24Hours = req.is24Hours,
            hours = req.hours?.takeUnless { req.is24Hours }?.mapValues { it.value.toDto() },
            amenities = req.amenities,
            equipmentIds = req.equipmentIds,
            equipmentSpecs = emptyMap(),
            isDefault = false,
            isActive = true,
            createdAt = now,
            updatedAt = now,
        )
        support.createLocal(
            table = MirrorTables.LOCATIONS,
            id = id,
            payloadJson = dtoAdapter.toJson(dto),
            lastUpdate = System.currentTimeMillis(),
        )
        dto.toDomain()
    }

    suspend fun update(
        locationId: String,
        req: UpdateLocationRequest,
    ): Result<Location> = runCatching {
        val current = mirroredDto(locationId) ?: api.get(locationId)
        val merged = current.copy(
            name = req.name ?: current.name,
            address = req.address ?: current.address,
            is24Hours = req.is24Hours ?: current.is24Hours,
            hours = req.hours?.mapValues { it.value.toDto() } ?: current.hours,
            amenities = req.amenities ?: current.amenities,
            equipmentIds = req.equipmentIds ?: current.equipmentIds,
            updatedAt = Instant.now().toString(),
        )
        support.updateLocal(
            table = MirrorTables.LOCATIONS,
            id = locationId,
            payloadJson = dtoAdapter.toJson(merged),
            lastUpdate = System.currentTimeMillis(),
        )
        merged.toDomain()
    }

    suspend fun delete(locationId: String): Result<Unit> = runCatching {
        support.deleteLocal(MirrorTables.LOCATIONS, locationId, System.currentTimeMillis())
    }

    suspend fun setDefault(locationId: String): Result<Unit> = runCatching {
        api.setDefault(locationId).unitOrThrow()
        // Default is exclusive across rows; re-fill the whole list so the prior
        // default's mirror row loses its flag too.
        fillMirror(includeInactive = false)
    }

    suspend fun uploadCoverPhoto(
        locationId: String,
        file: PendingUpload,
    ): Result<String> = runCatching {
        val bytes = file.source().use { it.readBytes() }
        val url = baseUrl.trimEnd('/') + "/api/me/gyms/$locationId/photo"
        val responseText = multipart.upload(
            url = url,
            fileFieldName = "file",
            fileName = file.filename,
            mediaType = file.mimeType,
            bytes = bytes,
        )
        // The endpoint returns either the updated Location JSON or a small
        // { "coverPhotoUrl": "..." } payload; pull the URL out defensively.
        runCatching { refreshOne(locationId) }
        parseCoverPhotoUrl(responseText)
    }

    suspend fun deleteCoverPhoto(locationId: String): Result<Unit> = runCatching {
        api.deleteCoverPhoto(locationId).unitOrThrow()
        runCatching { refreshOne(locationId) }
        Unit
    }

    suspend fun addEquipment(
        locationId: String,
        equipmentId: String,
    ): Result<Unit> = runCatching {
        api.addEquipment(locationId, equipmentId).unitOrThrow()
        runCatching { refreshOne(locationId) }
        Unit
    }

    suspend fun removeEquipment(
        locationId: String,
        equipmentId: String,
    ): Result<Unit> = runCatching {
        api.removeEquipment(locationId, equipmentId).unitOrThrow()
        runCatching { refreshOne(locationId) }
        Unit
    }

    suspend fun updateEquipmentSpecs(
        locationId: String,
        equipmentId: String,
        specs: Map<String, Any?>,
    ): Result<Location> = runCatching {
        val dto = api.updateEquipmentSpecs(locationId, equipmentId, SpecsPatchDto(specs))
        support.refreshInto(MirrorTables.LOCATIONS, listOf(dto.toRefreshRow()))
        dto.toDomain()
    }

    /** Pull the full gym list from the network into the mirror as SYNCED rows. */
    private suspend fun fillMirror(includeInactive: Boolean) {
        val dtos = api.list(if (includeInactive) "inactive" else null)
        support.refreshInto(MirrorTables.LOCATIONS, dtos.map { it.toRefreshRow() })
    }

    /** Fetch one gym from the network and upsert it into the mirror; returns the DTO. */
    private suspend fun refreshOne(locationId: String): LocationDto {
        val dto = api.get(locationId)
        support.refreshInto(MirrorTables.LOCATIONS, listOf(dto.toRefreshRow()))
        return dto
    }

    private suspend fun mirroredDto(locationId: String): LocationDto? =
        dao.getById(locationId)?.let { decode(it.payloadJson) }

    private fun decode(json: String): LocationDto? =
        runCatching { dtoAdapter.fromJson(json) }.getOrNull()

    private fun LocationDto.toRefreshRow() = MirrorRepositorySupport.RefreshRow(
        id = locationId,
        payloadJson = dtoAdapter.toJson(this),
        lastUpdate = runCatching { Instant.parse(updatedAt).toEpochMilli() }
            .getOrDefault(System.currentTimeMillis()),
    )

    private fun parseCoverPhotoUrl(text: String): String {
        if (text.isBlank()) return ""
        return runCatching {
            val map = mapAdapter.fromJson(text)
            (map?.get("coverPhotoUrl") as? String)
                ?: (map?.get("url") as? String)
                ?: text
        }.getOrDefault(text)
    }

    private fun Response<Unit>.unitOrThrow() {
        if (!isSuccessful) {
            throw retrofit2.HttpException(this)
        }
    }
}
