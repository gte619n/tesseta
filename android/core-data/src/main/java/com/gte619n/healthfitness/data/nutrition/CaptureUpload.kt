package com.gte619n.healthfitness.data.nutrition

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Instant-feel photo capture: the capture screen pops back to the Today page
 * the moment the shutter result is in hand — the multipart upload happens in
 * the background via [CaptureUploadWorker], with the JPEG parked in a cache
 * file so the work survives process death and retries across connectivity
 * blips.
 *
 * While the upload is in flight the Today page shows a synthetic
 * "Uploading photo…" row sourced from [PendingCaptureStore] (in-memory; after
 * a process death the row is gone but the worker still completes and the entry
 * appears on the next day refresh).
 */
@Singleton
class PendingCaptureStore @Inject constructor() {

    /** One in-flight capture upload: which day/meal it will log to. */
    data class PendingCapture(
        val id: String,
        val date: String,
        val mealWire: String,
        val startedAtMillis: Long,
    )

    private val _pending = MutableStateFlow<List<PendingCapture>>(emptyList())
    val pending: StateFlow<List<PendingCapture>> = _pending.asStateFlow()

    fun add(capture: PendingCapture) = _pending.update { it + capture }

    fun remove(id: String) = _pending.update { list -> list.filterNot { it.id == id } }
}

/** Writes the JPEG to cache and enqueues the background upload. */
@Singleton
class CaptureUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val store: PendingCaptureStore,
) {
    /** Enqueue an upload; returns immediately (nothing blocks on the network). */
    fun enqueue(date: String, mealWire: String, jpeg: ByteArray): String {
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "capture-upload-$id.jpg")
        file.writeBytes(jpeg)
        store.add(
            PendingCaptureStore.PendingCapture(
                id = id,
                date = date,
                mealWire = mealWire,
                startedAtMillis = System.currentTimeMillis(),
            ),
        )
        val request = OneTimeWorkRequestBuilder<CaptureUploadWorker>()
            .setInputData(
                workDataOf(
                    CaptureUploadWorker.KEY_ID to id,
                    CaptureUploadWorker.KEY_DATE to date,
                    CaptureUploadWorker.KEY_MEAL to mealWire,
                    CaptureUploadWorker.KEY_PATH to file.absolutePath,
                ),
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork("hf-capture-upload-$id", ExistingWorkPolicy.KEEP, request)
        return id
    }
}

/**
 * Uploads one parked capture photo. On success the server's ANALYZING
 * placeholder is pulled into the mirror (so the Today page swaps the synthetic
 * row for the real one) and the cache file is deleted. Failures retry with
 * backoff up to [MAX_ATTEMPTS]; a terminal failure clears the synthetic row —
 * the photo was never logged, which the user can see and redo.
 */
@HiltWorker
class CaptureUploadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val capture: NutritionCaptureRepository,
    private val nutrition: NutritionRepository,
    private val store: PendingCaptureStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val date = inputData.getString(KEY_DATE) ?: return cleanup(id)
        val meal = inputData.getString(KEY_MEAL) ?: return cleanup(id)
        val path = inputData.getString(KEY_PATH) ?: return cleanup(id)
        val file = File(path)
        if (!file.exists()) return cleanup(id)

        return try {
            capture.captureMeal(date, meal, file.readBytes())
            runCatching { nutrition.refreshDay(date) }
            file.delete()
            store.remove(id)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                file.delete()
                cleanup(id)
            }
        }
    }

    private fun cleanup(id: String): Result {
        store.remove(id)
        return Result.failure()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_DATE = "date"
        const val KEY_MEAL = "meal"
        const val KEY_PATH = "path"
        private const val MAX_ATTEMPTS = 5
    }
}
