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
import com.gte619n.healthfitness.data.db.entity.NutritionOpEntity
import com.gte619n.healthfitness.data.db.entity.NutritionOpType
import com.gte619n.healthfitness.domain.nutrition.EntryRequest
import com.gte619n.healthfitness.domain.nutrition.FoodCreateRequest
import com.gte619n.healthfitness.domain.nutrition.LabelCaptureFood
import com.gte619n.healthfitness.domain.nutrition.ServingSize
import com.gte619n.healthfitness.domain.nutrition.forPortion
import com.squareup.moshi.Moshi
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable execution of the nutrition AI-create flows (describe-a-meal,
 * log-a-saved-meal, confirm a photographed meal's items, confirm a scanned
 * label, upload a meal photo).
 *
 * Previously these fired their network POST inside `viewModelScope`, so a
 * process death / backgrounding before the POST landed lost the log. Now the
 * ViewModel/repository writes a durable [NutritionOpEntity] row (+ a cache file
 * for a JPEG) and schedules [NutritionOpWorker]. The row survives process death,
 * so the operation still completes and its synthetic "logging…" placeholder still
 * renders across a kill. Replays are safe: the op carries a stable
 * `Idempotency-Key` and client-minted ids, so a re-POST returns the same server
 * document instead of duplicating it.
 */
@Singleton
class NutritionOpEnqueuer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workManager: WorkManager,
    private val store: NutritionOpStore,
    moshi: Moshi,
) {
    private val describeAdapter = moshi.adapter(DescribeAsyncPayload::class.java)
    private val savedMealAdapter = moshi.adapter(LogSavedMealPayload::class.java)
    private val labelAdapter = moshi.adapter(ConfirmLabelPayload::class.java)
    private val itemsAdapter = moshi.adapter(ConfirmMealItemsPayload::class.java)

    /** Enqueue a fire-and-forget text-describe. Returns the op id. */
    suspend fun enqueueDescribeAsync(date: String, mealWire: String, description: String): String =
        enqueue(
            type = NutritionOpType.DESCRIBE_ASYNC,
            date = date,
            mealWire = mealWire,
            label = description,
            payloadJson = describeAdapter.toJson(DescribeAsyncPayload(description)),
        )

    /** Enqueue a saved-meal log by [mealId] (reuses its plated photo when READY). */
    suspend fun enqueueLogSavedMeal(
        date: String,
        mealWire: String,
        mealId: String,
        label: String,
        knownImageUrl: String?,
        knownImageStatus: String,
    ): String =
        enqueue(
            type = NutritionOpType.LOG_SAVED_MEAL,
            date = date,
            mealWire = mealWire,
            label = label,
            payloadJson = savedMealAdapter.toJson(
                LogSavedMealPayload(mealId, knownImageUrl, knownImageStatus),
            ),
        )

    /** Enqueue a scanned-label confirm (create the food, then log one entry). */
    suspend fun enqueueConfirmLabel(
        date: String,
        mealWire: String,
        draft: LabelCaptureFood,
        servingIndex: Int,
        quantity: Double,
    ): String =
        enqueue(
            type = NutritionOpType.CONFIRM_LABEL,
            date = date,
            mealWire = mealWire,
            label = draft.name,
            payloadJson = labelAdapter.toJson(
                ConfirmLabelPayload(draft, servingIndex, quantity, UUID.randomUUID().toString()),
            ),
        )

    /** Enqueue a photographed-meal confirm (create foods for unmatched items, log each). */
    suspend fun enqueueConfirmMealItems(
        date: String,
        mealWire: String,
        items: List<com.gte619n.healthfitness.domain.nutrition.MealCaptureItem>,
    ): String {
        val confirmItems = items.map {
            ConfirmMealItem(it, UUID.randomUUID().toString(), UUID.randomUUID().toString())
        }
        val label = if (items.size == 1) items.first().name else "${items.size} items"
        return enqueue(
            type = NutritionOpType.CONFIRM_MEAL_ITEMS,
            date = date,
            mealWire = mealWire,
            label = label,
            payloadJson = itemsAdapter.toJson(ConfirmMealItemsPayload(confirmItems)),
        )
    }

    /** Enqueue a meal-photo upload; the JPEG is parked in a cache file. */
    suspend fun enqueueCapturePhoto(date: String, mealWire: String, jpeg: ByteArray): String {
        val id = UUID.randomUUID().toString()
        val file = File(context.cacheDir, "nutrition-op-$id.jpg")
        file.writeBytes(jpeg)
        return enqueue(
            id = id,
            type = NutritionOpType.CAPTURE_PHOTO,
            date = date,
            mealWire = mealWire,
            label = "Analyzing photo…",
            payloadJson = null,
            jpegPath = file.absolutePath,
        )
    }

    private suspend fun enqueue(
        type: NutritionOpType,
        date: String,
        mealWire: String,
        label: String,
        payloadJson: String?,
        jpegPath: String? = null,
        id: String = UUID.randomUUID().toString(),
    ): String = withContext(NonCancellable) {
        // NonCancellable so enqueuing survives the caller's scope being cancelled
        // the instant after they tapped "log" — the op row (the durable source of
        // truth) and its WorkManager trigger must both land.
        val now = System.currentTimeMillis()
        store.add(
            NutritionOpEntity(
                id = id,
                type = type.name,
                date = date,
                mealWire = mealWire,
                clientEntryId = UUID.randomUUID().toString(),
                idempotencyKey = id,
                payloadJson = payloadJson,
                jpegPath = jpegPath,
                label = label,
                attempts = 0,
                nextAttemptAt = now,
                createdAt = now,
            ),
        )
        val request = OneTimeWorkRequestBuilder<NutritionOpWorker>()
            .setInputData(workDataOf(NutritionOpWorker.KEY_ID to id))
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork("hf-nutrition-op-$id", ExistingWorkPolicy.KEEP, request)
        id
    }
}

/**
 * Executes one durable nutrition op. Reloads it from the store (the durable
 * source of truth), dispatches by type, and on success deletes the op row (and
 * any cache file) — which drops the synthetic placeholder as the real entry
 * lands. Failures retry with backoff up to [MAX_ATTEMPTS]; a terminal failure
 * clears the row so the user sees the log didn't stick and can redo it.
 */
@HiltWorker
class NutritionOpWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val store: NutritionOpStore,
    private val nutrition: NutritionRepository,
    private val foods: FoodRepository,
    private val capture: NutritionCaptureRepository,
    private val previews: CapturePreviewStore,
    moshi: Moshi,
) : CoroutineWorker(appContext, params) {

    private val describeAdapter = moshi.adapter(DescribeAsyncPayload::class.java)
    private val savedMealAdapter = moshi.adapter(LogSavedMealPayload::class.java)
    private val labelAdapter = moshi.adapter(ConfirmLabelPayload::class.java)
    private val itemsAdapter = moshi.adapter(ConfirmMealItemsPayload::class.java)

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val op = store.find(id) ?: return Result.success() // already completed/removed
        return try {
            when (NutritionOpType.valueOf(op.type)) {
                NutritionOpType.DESCRIBE_ASYNC -> {
                    val p = describeAdapter.fromJson(op.payloadJson!!)!!
                    nutrition.runDescribeAsync(op.date, op.mealWire, p.description, op.clientEntryId, op.idempotencyKey)
                }
                NutritionOpType.LOG_SAVED_MEAL -> {
                    val p = savedMealAdapter.fromJson(op.payloadJson!!)!!
                    nutrition.runLogSavedMeal(
                        op.date, op.mealWire, p.mealId, op.clientEntryId, op.idempotencyKey,
                        p.knownImageUrl, p.knownImageStatus,
                    )
                }
                NutritionOpType.CONFIRM_LABEL -> confirmLabel(op)
                NutritionOpType.CONFIRM_MEAL_ITEMS -> confirmMealItems(op)
                NutritionOpType.CAPTURE_PHOTO -> capturePhoto(op)
            }
            // On success, DON'T delete the JPEG: a photo capture just handed it to
            // the preview store, which owns it until the generated image lands.
            store.remove(op.id)
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS - 1) {
                Result.retry()
            } else {
                // Terminal failure: the capture never produced an entry/preview, so
                // reclaim its cache file here.
                op.jpegPath?.let { runCatching { File(it).delete() } }
                store.remove(op.id)
                Result.failure()
            }
        }
    }

    private suspend fun confirmLabel(op: NutritionOpEntity) {
        val p = labelAdapter.fromJson(op.payloadJson!!)!!
        val created = foods.create(
            FoodCreateRequest(
                name = p.draft.name,
                brand = p.draft.brand,
                barcode = p.draft.barcode,
                macrosPer100g = p.draft.macrosPer100g,
                servingSizes = p.draft.servingSizes,
                defaultServingIndex = p.draft.defaultServingIndex,
                id = p.clientFoodId,
            ),
            idempotencyKey = "${op.idempotencyKey}::food",
        )
        val serving = created.servingSizes.getOrNull(p.servingIndex)
            ?: p.draft.servingSizes.getOrNull(p.servingIndex)
        val grams = serving?.grams ?: 100.0
        val label = serving?.label ?: "1 serving"
        val macros = created.macrosPer100g.forPortion(grams, p.quantity)
        nutrition.addEntry(
            op.date,
            EntryRequest(
                meal = op.mealWire,
                foodId = created.foodId,
                foodName = created.name,
                servingLabel = label,
                servingGrams = grams,
                quantity = p.quantity,
                macros = macros,
                source = "LABEL",
            ),
            entryId = op.clientEntryId,
        )
    }

    private suspend fun confirmMealItems(op: NutritionOpEntity) {
        val p = itemsAdapter.fromJson(op.payloadJson!!)!!
        p.items.forEachIndexed { i, ci ->
            val foodId = ci.item.matchedFoodId ?: foods.create(
                FoodCreateRequest(
                    name = ci.item.name,
                    macrosPer100g = ci.item.macrosPer100g,
                    servingSizes = listOf(
                        ServingSize(ci.item.suggestedServingLabel, ci.item.estimatedPortionGrams),
                    ),
                    defaultServingIndex = 0,
                    id = ci.clientFoodId,
                ),
                idempotencyKey = "${op.idempotencyKey}::food::$i",
            ).foodId
            nutrition.addEntry(
                op.date,
                EntryRequest(
                    meal = op.mealWire,
                    foodId = foodId,
                    foodName = ci.item.name,
                    servingLabel = ci.item.suggestedServingLabel,
                    servingGrams = ci.item.estimatedPortionGrams,
                    quantity = 1.0,
                    macros = ci.item.macrosForPortion,
                    source = "PHOTO",
                ),
                entryId = ci.clientEntryId,
            )
        }
    }

    private suspend fun capturePhoto(op: NutritionOpEntity) {
        val path = op.jpegPath ?: return
        val file = File(path)
        if (!file.exists()) return
        val entry = capture.captureMeal(op.date, op.mealWire, file.readBytes())
        // Pull the server's ANALYZING placeholder into the mirror FIRST, so the
        // entry exists locally before we associate the photo…
        nutrition.refreshDay(op.date)
        // …then hand the captured JPEG to the row for this server entry. The
        // preview-store observer re-assembles the day mirror-only (no network) and
        // shows the photo on the real row immediately; the store owns the file now
        // and deletes it once the generated image lands READY.
        previews.put(entry.entryId, path)
    }

    companion object {
        const val KEY_ID = "id"
        private const val MAX_ATTEMPTS = 5
    }
}
