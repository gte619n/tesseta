package com.gte619n.healthfitness.data.bodycomposition.api

import com.gte619n.healthfitness.data.bodycomposition.dto.DexaScanDto
import com.gte619n.healthfitness.data.bodycomposition.dto.DexaScanSummaryDto
import com.gte619n.healthfitness.data.bodycomposition.dto.PatchFieldRequest
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.Streaming

interface DexaScanApi {
    @GET("api/me/dexa/scans")
    suspend fun list(): List<DexaScanSummaryDto>

    @GET("api/me/dexa/scans/{scanId}")
    suspend fun get(@Path("scanId") scanId: String): DexaScanDto

    @PATCH("api/me/dexa/scans/{scanId}/field")
    suspend fun patchField(
        @Path("scanId") scanId: String,
        @Body body: PatchFieldRequest,
    ): DexaScanDto

    @DELETE("api/me/dexa/scans/{scanId}")
    suspend fun delete(@Path("scanId") scanId: String)

    @Streaming
    @GET("api/me/dexa/scans/{scanId}/pdf")
    suspend fun downloadPdf(@Path("scanId") scanId: String): ResponseBody
}
