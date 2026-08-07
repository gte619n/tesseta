package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.domain.nutrition.LabelCaptureFood
import com.gte619n.healthfitness.domain.nutrition.MealCaptureItem

/**
 * Type-specific payloads for a durable nutrition op, serialized to
 * `NutritionOpEntity.payloadJson` (Moshi). Each op type reads exactly one of
 * these. Photo capture has no payload (its JPEG lives in a cache file).
 *
 * Client-minted ids (`clientFoodId`, `clientEntryId`) are frozen at enqueue time
 * and reused on every worker retry so a replay overwrites the same server
 * document rather than creating a duplicate.
 */

/** DESCRIBE_ASYNC: log an ANALYZING placeholder named with the user's [description]. */
data class DescribeAsyncPayload(val description: String)

/** LOG_SAVED_MEAL: log the saved meal [mealId], reusing its plated photo when READY. */
data class LogSavedMealPayload(
    val mealId: String,
    val knownImageUrl: String?,
    val knownImageStatus: String,
)

/** CONFIRM_LABEL: create the catalog food from a scanned label, then log one entry. */
data class ConfirmLabelPayload(
    val draft: LabelCaptureFood,
    val servingIndex: Int,
    val quantity: Double,
    val clientFoodId: String,
)

/** CONFIRM_MEAL_ITEMS: create foods for unmatched items, then log one entry each. */
data class ConfirmMealItemsPayload(val items: List<ConfirmMealItem>)

/** One item of a photographed meal, with the ids to reuse on replay. */
data class ConfirmMealItem(
    val item: MealCaptureItem,
    /** Client-minted id for the catalog food we create when the item has no match. */
    val clientFoodId: String,
    /** Client-minted id for the logged entry, so a replay overwrites it. */
    val clientEntryId: String,
)
