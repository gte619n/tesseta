package com.gte619n.healthfitness.shared.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * IMPL-IOS-01 (logging spine) — an inert [NutritionOpQueue] for the online-first
 * Today screen. It observes an empty op list (so the VM projects no synthetic
 * "logging…" rows and never re-fetches off an op completion), and refuses the
 * enqueue entry points: the durable capture/op rail — which parks a photo, uploads
 * it, and survives process death — lands with the sync/outbox layer. Until then
 * the Today screen is read + direct entry logging only.
 */
class NoopNutritionOpQueue : NutritionOpQueue {

    override fun observeAll(): Flow<List<NutritionOp>> = flowOf(emptyList())

    override suspend fun enqueueCapturePhoto(date: String, mealWire: String, jpeg: ByteArray): String =
        throw NotImplementedError("Durable photo capture is not wired on iOS yet")

    override suspend fun enqueueConfirmMealItems(date: String, mealWire: String, items: List<MealCaptureItem>): String =
        throw NotImplementedError("Durable meal-item confirm is not wired on iOS yet")

    override suspend fun enqueueConfirmLabel(
        date: String,
        mealWire: String,
        draft: LabelCaptureFood,
        servingIndex: Int,
        quantity: Double,
    ): String = throw NotImplementedError("Durable label confirm is not wired on iOS yet")
}
