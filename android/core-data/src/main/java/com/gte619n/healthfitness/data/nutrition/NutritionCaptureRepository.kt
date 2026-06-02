package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.LabelCaptureResponse
import com.gte619n.healthfitness.domain.nutrition.MealCaptureResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

// Thin wrapper over NutritionCaptureApi. Takes raw JPEG bytes from CameraX and
// builds the multipart request; the ViewModels never touch OkHttp types.
@Singleton
class NutritionCaptureRepository @Inject constructor(
    private val api: NutritionCaptureApi,
) {
    suspend fun analyzeMeal(jpegBytes: ByteArray): MealCaptureResponse =
        api.analyzeMeal(photoPart(jpegBytes))

    /**
     * Capture a meal/product photo and log it asynchronously. Returns the
     * ANALYZING placeholder entry; the server fills it in in the background.
     */
    suspend fun captureMeal(date: String, mealWire: String, jpegBytes: ByteArray): Entry {
        val mealPart = mealWire.toRequestBody("text/plain".toMediaType())
        return api.captureMeal(date, mealPart, photoPart(jpegBytes))
    }

    suspend fun analyzeLabel(jpegBytes: ByteArray, barcode: String? = null): LabelCaptureResponse {
        val barcodePart = barcode?.toRequestBody("text/plain".toMediaType())
        return api.analyzeLabel(photoPart(jpegBytes), barcodePart)
    }

    private fun photoPart(jpegBytes: ByteArray): MultipartBody.Part {
        val body = jpegBytes.toRequestBody("image/jpeg".toMediaType(), 0, jpegBytes.size)
        return MultipartBody.Part.createFormData("photo", "capture.jpg", body)
    }
}
