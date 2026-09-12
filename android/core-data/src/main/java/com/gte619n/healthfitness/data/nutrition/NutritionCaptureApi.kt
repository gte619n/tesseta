package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.LabelCaptureResponse
import com.gte619n.healthfitness.domain.nutrition.MealCaptureResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

// AI capture (phone only, IMPL-13). Multipart photo upload → Gemini-backed
// proposal. The matching backend endpoints are built in parallel; this codes
// to the contract in docs/specs/IMPL-13-nutrition-tracking.md.
interface NutritionCaptureApi {

    // Meal photo → itemized proposal (nothing is saved server-side).
    @Multipart
    @POST("api/nutrition/capture/meal")
    suspend fun analyzeMeal(
        @Part photo: MultipartBody.Part,
    ): MealCaptureResponse

    // Meal/product photo → log asynchronously. Returns an ANALYZING placeholder
    // entry immediately; the server itemizes/names it and generates images in
    // the background, and the day view is polled until it fills in.
    @Multipart
    @POST("api/me/nutrition/{date}/capture-meal")
    suspend fun captureMeal(
        @Path("date") date: String,
        @Part("meal") meal: RequestBody,
        @Part photo: MultipartBody.Part,
    ): Entry

    // Label photo (+ optional scanned barcode) → packaged-food proposal.
    @Multipart
    @POST("api/nutrition/capture/label")
    suspend fun analyzeLabel(
        @Part photo: MultipartBody.Part,
        @Part("barcode") barcode: RequestBody? = null,
    ): LabelCaptureResponse

    // Remove Leftovers (IMPL-LEFTOVER-01): upload a photo of what's left on the
    // plate. Returns 202 + the entry (leftover status now ANALYZING); the backend
    // job compares it to the original meal photo and estimates what was eaten.
    // Mirrors captureMeal's multipart shape exactly (`photo` part, image/jpeg).
    @Multipart
    @POST("api/me/nutrition/{date}/entries/{entryId}/leftovers/analyze")
    suspend fun analyzeLeftovers(
        @Path("date") date: String,
        @Path("entryId") entryId: String,
        @Part photo: MultipartBody.Part,
    ): Entry
}
