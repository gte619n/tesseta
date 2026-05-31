package com.gte619n.healthfitness.data.medications

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** Multi-tenant medications REST surface (base path `/api/me/medications`). */
internal interface MedicationsApi {

    @GET("api/me/medications")
    suspend fun list(@Query("status") status: String? = null): List<MedicationDto>

    @GET("api/me/medications/{id}")
    suspend fun get(@Path("id") id: String): MedicationDetailDto

    @POST("api/me/medications")
    suspend fun create(@Body body: CreateMedicationDto): MedicationDto

    @PUT("api/me/medications/{id}")
    suspend fun update(@Path("id") id: String, @Body body: UpdateMedicationDto): MedicationDto

    // [PR#8] change dose effective on a date (dated history)
    @POST("api/me/medications/{id}/dosage")
    suspend fun changeDose(@Path("id") id: String, @Body body: ChangeDoseDto): MedicationDto

    @POST("api/me/medications/{id}/discontinue")
    suspend fun discontinue(@Path("id") id: String, @Body body: DiscontinueDto): MedicationDto

    // [PR#8] resume a discontinued medication
    @POST("api/me/medications/{id}/reactivate")
    suspend fun reactivate(@Path("id") id: String, @Body body: ReactivateDto): MedicationDto

    @DELETE("api/me/medications/{id}")
    suspend fun delete(@Path("id") id: String)

    @GET("api/me/medications/today")
    suspend fun today(): List<TodaysDoseDto>
}

internal interface AdherenceApi {
    @POST("api/me/medications/{id}/adherence")
    suspend fun log(@Path("id") id: String, @Body body: LogDoseDto)

    @DELETE("api/me/medications/{id}/adherence/{date}/{window}")
    suspend fun undo(
        @Path("id") id: String,
        @Path("date") date: String,
        @Path("window") window: String,
    )
}

internal interface DrugsApi {
    @GET("api/drugs")
    suspend fun catalog(): List<DrugDto>

    @GET("api/drugs/{id}")
    suspend fun get(@Path("id") id: String): DrugDto
}
