package com.gte619n.healthfitness.data.medications

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit service for user medications + today's doses. Internal —
 * everything north of the repository talks to [MedicationRepository] not
 * this interface.
 *
 * Endpoint paths match `MedicationController` and `TodaysDosesController`
 * on the backend exactly. `status` accepts the enum-name (`ACTIVE` /
 * `DISCONTINUED`) on the wire.
 */
internal interface MedicationsApi {

    @GET("api/me/medications")
    suspend fun list(@Query("status") status: String? = null): List<MedicationDto>

    @GET("api/me/medications/{id}")
    suspend fun get(@Path("id") medicationId: String): MedicationDetailDto

    @POST("api/me/medications")
    suspend fun create(@Body body: CreateMedicationDto): MedicationDto

    @PUT("api/me/medications/{id}")
    suspend fun update(
        @Path("id") medicationId: String,
        @Body body: UpdateMedicationDto,
    ): MedicationDto

    @POST("api/me/medications/{id}/dosage")
    suspend fun changeDose(
        @Path("id") medicationId: String,
        @Body body: ChangeDoseDto,
    ): MedicationDto

    @POST("api/me/medications/{id}/discontinue")
    suspend fun discontinue(
        @Path("id") medicationId: String,
        @Body body: DiscontinueDto,
    ): MedicationDto

    @DELETE("api/me/medications/{id}")
    suspend fun delete(@Path("id") medicationId: String)

    @GET("api/me/medications/today")
    suspend fun today(): List<TodaysDoseDto>
}

/**
 * Retrofit service for adherence logging. Endpoint paths and DELETE path
 * params match `AdherenceController`. Window enum is serialised as its
 * name (`MORNING`, etc.); date as ISO-8601 (`yyyy-MM-dd`).
 */
internal interface AdherenceApi {

    @POST("api/me/medications/{id}/adherence")
    suspend fun log(
        @Path("id") medicationId: String,
        @Body body: LogDoseDto,
    )

    @DELETE("api/me/medications/{id}/adherence/{date}/{window}")
    suspend fun undo(
        @Path("id") medicationId: String,
        @Path("date") date: String,
        @Path("window") window: String,
    )
}

/**
 * Retrofit service for the shared drug catalog.
 *  - `catalog(q)` → `GET /api/drugs?q=...` (or no query for the full list).
 *  - `get(id)`    → `GET /api/drugs/{id}`.
 *
 * SSE lookup uses [DrugLookupStreamClient] directly (Retrofit doesn't
 * model SSE responses well).
 */
internal interface DrugsApi {

    @GET("api/drugs")
    suspend fun catalog(@Query("q") query: String? = null): List<DrugDto>

    @GET("api/drugs/{id}")
    suspend fun get(@Path("id") drugId: String): DrugDto
}
