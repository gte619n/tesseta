package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.domain.workouts.CreateEquipmentRequest
import com.gte619n.healthfitness.domain.workouts.Equipment
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EquipmentRepository @Inject constructor(
    private val api: EquipmentApi,
) {

    suspend fun searchCatalog(
        search: String? = null,
        category: String? = null,
        subcategory: String? = null,
    ): Result<List<Equipment>> = runCatching {
        api.search(search = search, category = category, sub = subcategory).map { it.toDomain() }
    }

    suspend fun get(equipmentId: String): Result<Equipment> = runCatching {
        api.get(equipmentId).toDomain()
    }

    suspend fun categories(): Result<Map<String, List<String>>> = runCatching {
        api.categories()
    }

    suspend fun submit(req: CreateEquipmentRequest): Result<Equipment> = runCatching {
        api.submit(req.toDto()).toDomain()
    }

    suspend fun mySubmissions(): Result<List<Equipment>> = runCatching {
        api.mySubmissions().map { it.toDomain() }
    }

    suspend fun deleteSubmission(equipmentId: String): Result<Unit> = runCatching {
        val response: Response<Unit> = api.delete(equipmentId)
        if (!response.isSuccessful) throw retrofit2.HttpException(response)
    }
}
