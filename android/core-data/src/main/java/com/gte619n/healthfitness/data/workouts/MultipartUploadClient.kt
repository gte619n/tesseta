package com.gte619n.healthfitness.data.workouts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Plain multipart/form-data upload helper (no SSE). Used for cover-photo
 * uploads (`POST /api/me/gyms/{id}/photo`). Lives in the workouts data
 * package rather than core-data/net (which is off-limits for this IMPL) and
 * can be promoted to a shared location later for blood/DEXA uploads.
 *
 * Auth/base-url are handled by the injected [OkHttpClient]'s interceptors
 * being shared with the app's Retrofit stack; callers pass a fully-qualified
 * [url].
 */
@Singleton
class MultipartUploadClient @Inject constructor(
    private val client: OkHttpClient,
) {
    /** Uploads [bytes] as a single form-data file part and returns the response body string. */
    suspend fun upload(
        url: String,
        fileFieldName: String,
        fileName: String,
        mediaType: String,
        bytes: ByteArray,
        extraFields: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                fileFieldName,
                fileName,
                bytes.toRequestBody(mediaType.toMediaTypeOrNull(), 0, bytes.size),
            )
            .apply { extraFields.forEach { (k, v) -> addFormDataPart(k, v) } }
            .build()

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw java.io.IOException("Upload failed: HTTP ${response.code} $text")
            }
            text
        }
    }
}
