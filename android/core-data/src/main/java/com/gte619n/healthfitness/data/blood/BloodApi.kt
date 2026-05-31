package com.gte619n.healthfitness.data.blood

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url

internal interface BloodApi {
    @GET("api/me/blood")
    suspend fun listReadings(): List<BloodReadingDto>

    @POST("api/me/blood")
    suspend fun createReading(@Body body: CreateReadingRequestDto): BloodReadingDto

    @DELETE("api/me/blood/{readingId}")
    suspend fun deleteReading(@Path("readingId") id: String)

    @GET("api/me/blood/reports")
    suspend fun listReports(): List<BloodTestReportDto>

    @GET("api/me/blood/reports/{reportId}")
    suspend fun getReport(@Path("reportId") id: String): BloodTestReportDto

    @DELETE("api/me/blood/reports/{reportId}")
    suspend fun deleteReport(@Path("reportId") id: String)

    /** Streams the raw PDF bytes for a report. [path] is the report's relative download path. */
    @Streaming
    @GET
    suspend fun downloadPdf(@Url path: String): ResponseBody
}
