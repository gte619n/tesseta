package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.LabelCaptureResponse
import com.gte619n.healthfitness.domain.nutrition.MealCaptureResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

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

    // Label photo (+ optional scanned barcode) → packaged-food proposal.
    @Multipart
    @POST("api/nutrition/capture/label")
    suspend fun analyzeLabel(
        @Part photo: MultipartBody.Part,
        @Part("barcode") barcode: RequestBody? = null,
    ): LabelCaptureResponse
}
