package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.data.net.BackendBaseUrl
import com.gte619n.healthfitness.domain.workouts.CreateLocationRequest
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.domain.workouts.LocationRepository
import com.gte619n.healthfitness.domain.workouts.PendingUpload
import com.gte619n.healthfitness.domain.workouts.UpdateLocationRequest
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepositoryImpl @Inject constructor(
    private val api: LocationApi,
    private val multipart: MultipartUploadClient,
    @BackendBaseUrl private val baseUrl: String,
    private val moshi: Moshi,
) : LocationRepository {

    private val mapAdapter =
        moshi.adapter<Map<String, Any?>>(
            Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java),
        )

    override suspend fun list(includeInactive: Boolean): Result<List<Location>> = runCatching {
        api.list(if (includeInactive) "inactive" else null).map { it.toDomain() }
    }

    override suspend fun get(locationId: String): Result<Location> = runCatching {
        api.get(locationId).toDomain()
    }

    override suspend fun create(req: CreateLocationRequest): Result<Location> = runCatching {
        api.create(req.toDto()).toDomain()
    }

    override suspend fun update(
        locationId: String,
        req: UpdateLocationRequest,
    ): Result<Location> = runCatching {
        api.update(locationId, req.toDto()).toDomain()
    }

    override suspend fun delete(locationId: String): Result<Unit> = runCatching {
        api.delete(locationId).unitOrThrow()
    }

    override suspend fun setDefault(locationId: String): Result<Unit> = runCatching {
        api.setDefault(locationId).unitOrThrow()
    }

    override suspend fun uploadCoverPhoto(
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
        parseCoverPhotoUrl(responseText)
    }

    override suspend fun deleteCoverPhoto(locationId: String): Result<Unit> = runCatching {
        api.deleteCoverPhoto(locationId).unitOrThrow()
    }

    override suspend fun addEquipment(
        locationId: String,
        equipmentId: String,
    ): Result<Unit> = runCatching {
        api.addEquipment(locationId, equipmentId).unitOrThrow()
    }

    override suspend fun removeEquipment(
        locationId: String,
        equipmentId: String,
    ): Result<Unit> = runCatching {
        api.removeEquipment(locationId, equipmentId).unitOrThrow()
    }

    override suspend fun updateEquipmentSpecs(
        locationId: String,
        equipmentId: String,
        specs: Map<String, Any?>,
    ): Result<Location> = runCatching {
        api.updateEquipmentSpecs(locationId, equipmentId, SpecsPatchDto(specs)).toDomain()
    }

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
