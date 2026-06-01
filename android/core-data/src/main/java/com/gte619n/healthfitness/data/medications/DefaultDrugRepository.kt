package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.medications.Drug
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.domain.medications.DrugRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class DefaultDrugRepository @Inject constructor(
    private val api: DrugsApi,
    private val lookupClient: DrugLookupStreamClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) : DrugRepository {

    override suspend fun catalog(): List<Drug> = withContext(io) {
        api.catalog().map { MedicationMapper.toDomain(it) }
    }

    override suspend fun get(drugId: String): Drug = withContext(io) {
        MedicationMapper.toDomain(api.get(drugId))
    }

    override fun lookupStream(query: String): Flow<DrugLookupEvent> = lookupClient.stream(query)
}
