package com.gte619n.healthfitness.data.bodycomposition.api

import com.gte619n.healthfitness.data.bodycomposition.dto.BodyCompositionReadingDto
import retrofit2.http.GET
import retrofit2.http.Query

interface BodyCompositionApi {
    @GET("api/me/body-composition")
    suspend fun list(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("metric") metric: String? = null,
    ): List<BodyCompositionReadingDto>
}
