package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.data.db.dao.CatalogCacheDao
import com.gte619n.healthfitness.data.db.entity.CatalogCacheEntity
import com.gte619n.healthfitness.domain.workouts.CreateEquipmentRequest
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.squareup.moshi.Moshi
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * offline-fix (ADR-0018) — the equipment catalog is a network-only reference set;
 * this repo now keeps a bounded [CatalogCacheEntity] read cache of the equipment
 * the user has actually fetched so detail re-entry paints instantly and works
 * offline. It is a plain local cache (type = [CACHE_TYPE]), NOT a sync mirror.
 *
 * Cache shape: the wire [EquipmentDto] is serialized with the workouts Moshi (which
 * carries the polymorphic [EquipmentSpecJsonAdapter] for the `specs` field, so the
 * base NetworkModule Moshi can't round-trip it). [get] revalidates over the network
 * and falls back to the cache on failure ([MedicationRepository.get] shape); [cached]
 * is a network-free seed for the ViewModels.
 */
@Singleton
class EquipmentRepository @Inject constructor(
    private val api: EquipmentApi,
    private val cacheDao: CatalogCacheDao,
    @Named("workoutsMoshi") moshi: Moshi,
) {

    private val dtoAdapter = moshi.adapter(EquipmentDto::class.java)

    suspend fun searchCatalog(
        search: String? = null,
        category: String? = null,
        subcategory: String? = null,
    ): Result<List<Equipment>> = runCatching {
        val dtos = api.search(search = search, category = category, sub = subcategory)
        // Cache each result by id so a later detail re-entry is offline-first.
        cache(dtos)
        dtos.map { it.toDomain() }
    }

    /**
     * Cache-first detail read: revalidate over the network and refresh the cache;
     * on failure fall back to the last-fetched cached copy (offline-first). Mirrors
     * [com.gte619n.healthfitness.data.medications.MedicationRepository.get].
     */
    suspend fun get(equipmentId: String): Result<Equipment> = runCatching {
        runCatching {
            val dto = api.get(equipmentId)
            cache(dto)
            dto.toDomain()
        }.getOrElse { e ->
            cached(equipmentId) ?: throw e
        }
    }

    /**
     * offline-fix: network-free seed from the local catalog cache — never hits the
     * network. The detail/override screens show this INSTANTLY on open (no spinner)
     * and then [get] revalidates. Returns null when the equipment hasn't been fetched
     * before (so the caller can fall through to a network load / error).
     */
    suspend fun cached(equipmentId: String): Equipment? =
        cacheDao.getById(CACHE_TYPE, equipmentId)?.let { decode(it.json)?.toDomain() }

    suspend fun categories(): Result<Map<String, List<String>>> = runCatching {
        api.categories()
    }

    suspend fun submit(req: CreateEquipmentRequest): Result<Equipment> = runCatching {
        val dto = api.submit(req.toDto())
        cache(dto)
        dto.toDomain()
    }

    suspend fun mySubmissions(): Result<List<Equipment>> = runCatching {
        val dtos = api.mySubmissions()
        cache(dtos)
        dtos.map { it.toDomain() }
    }

    suspend fun deleteSubmission(equipmentId: String): Result<Unit> = runCatching {
        val response: Response<Unit> = api.delete(equipmentId)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
    }

    // ---- catalog cache (offline-fix) ----

    private suspend fun cache(dto: EquipmentDto) = cache(listOf(dto))

    private suspend fun cache(dtos: List<EquipmentDto>) {
        if (dtos.isEmpty()) return
        val now = System.currentTimeMillis()
        cacheDao.upsertAll(
            dtos.map {
                CatalogCacheEntity(
                    type = CACHE_TYPE,
                    id = it.equipmentId,
                    json = dtoAdapter.toJson(it),
                    updatedAt = now,
                )
            },
        )
    }

    private fun decode(json: String): EquipmentDto? =
        runCatching { dtoAdapter.fromJson(json) }.getOrNull()

    private companion object {
        const val CACHE_TYPE = "equipment"
    }
}
