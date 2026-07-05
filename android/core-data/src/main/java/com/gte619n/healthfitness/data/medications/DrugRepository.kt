package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.db.dao.CatalogCacheDao
import com.gte619n.healthfitness.data.db.entity.CatalogCacheEntity
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * offline-fix (ADR-0018) — the drug catalog is a network-only reference set; this
 * repo now keeps a bounded [CatalogCacheEntity] read cache (type = [CACHE_TYPE]) of
 * the drugs it has actually fetched. [catalog]/[get] cache their results by id, [get]
 * falls back to the cache on a network failure, and [cached] is a network-free seed
 * (used e.g. by [MedicationRepository.withDrugs] to resolve embedded drugs offline).
 * Plain local cache, NOT a sync mirror.
 */
@Singleton
class DrugRepository @Inject internal constructor(
    private val api: DrugsApi,
    private val lookupClient: DrugLookupStreamClient,
    private val cacheDao: CatalogCacheDao,
    moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    private val dtoAdapter = moshi.adapter(DrugDto::class.java)

    suspend fun catalog(): List<Drug> = withContext(io) {
        val dtos = api.catalog()
        cache(dtos)
        dtos.map { MedicationMapper.toDomain(it) }
    }

    /**
     * Cache-first detail read: revalidate over the network and refresh the cache;
     * on failure fall back to the last-fetched cached copy so it resolves offline.
     */
    suspend fun get(drugId: String): Drug = withContext(io) {
        runCatching {
            val dto = api.get(drugId)
            cache(dto)
            MedicationMapper.toDomain(dto)
        }.getOrElse { e ->
            cached(drugId) ?: throw e
        }
    }

    /**
     * offline-fix: network-free seed from the local catalog cache — never hits the
     * network. Returns null when the drug hasn't been fetched before.
     */
    suspend fun cached(drugId: String): Drug? = withContext(io) {
        cacheDao.getById(CACHE_TYPE, drugId)?.let { decode(it.json) }?.let { MedicationMapper.toDomain(it) }
    }

    fun lookupStream(query: String): Flow<DrugLookupEvent> = lookupClient.stream(query)

    // ---- catalog cache (offline-fix) ----

    private suspend fun cache(dto: DrugDto) = cache(listOf(dto))

    private suspend fun cache(dtos: List<DrugDto>) {
        if (dtos.isEmpty()) return
        val now = System.currentTimeMillis()
        cacheDao.upsertAll(
            dtos.map {
                CatalogCacheEntity(
                    type = CACHE_TYPE,
                    id = it.drugId,
                    json = dtoAdapter.toJson(it),
                    updatedAt = now,
                )
            },
        )
    }

    private fun decode(json: String): DrugDto? =
        runCatching { dtoAdapter.fromJson(json) }.getOrNull()

    private companion object {
        const val CACHE_TYPE = "drug"
    }
}
