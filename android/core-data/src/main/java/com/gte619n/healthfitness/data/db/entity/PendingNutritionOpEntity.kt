package com.gte619n.healthfitness.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable queue of in-flight nutrition AI-create operations (describe-a-meal,
 * log-a-saved-meal, confirm a photographed meal's items, confirm a scanned
 * label). Unlike the generic [OutboxEntity], these creates are server-derived
 * (they mint AI images / itemizations) and don't map onto the outbox's REST
 * CRUD replay, so they ride their own rail: [NutritionOpEntity] is the durable
 * source of truth and a WorkManager job (`NutritionOpWorker`) executes the row
 * by [id].
 *
 * The row survives process death, so:
 *  - the operation still completes (the worker reloads it and POSTs), and
 *  - the UI can render its synthetic "logging…" placeholder row across a kill
 *    (the previous in-memory `PendingCaptureStore` lost that row on death).
 *
 * Replays are safe: [idempotencyKey] is sent as the `Idempotency-Key` header and
 * [clientEntryId] / any client-minted food ids are carried in [payloadJson], so a
 * re-POST returns the same server document instead of duplicating it.
 *
 * Indexed on `nextAttemptAt` for the drain's "due now" query.
 */
@Entity(
    tableName = "nutritionOps",
    indices = [Index("nextAttemptAt")],
)
data class NutritionOpEntity(
    /** Op id — also the WorkManager unique-work name and the cache-file suffix. */
    @PrimaryKey val id: String,
    /** One of [NutritionOpType]. */
    val type: String,
    /** The day (yyyy-MM-dd) this op logs to. */
    val date: String,
    /** The meal group the synthetic row belongs to and the entry logs into. */
    val mealWire: String,
    /** Client-minted id of the resulting entry, so a replay reuses it. */
    val clientEntryId: String,
    /** Value sent as the `Idempotency-Key` header on the create POST(s). */
    val idempotencyKey: String,
    /** Type-specific fields (Moshi JSON); shape decided by [type]. */
    val payloadJson: String?,
    /** Cache-file path for a JPEG payload (CAPTURE_PHOTO), else null. */
    val jpegPath: String?,
    /** Short human label for the synthetic placeholder row (e.g. the description). */
    val label: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val createdAt: Long,
)

/** The kinds of durable nutrition op the worker can execute. */
enum class NutritionOpType {
    /** Multipart meal-photo upload → server ANALYZING placeholder. */
    CAPTURE_PHOTO,

    /** Fire-and-forget text describe → server ANALYZING placeholder. */
    DESCRIBE_ASYNC,

    /** Log a saved meal by mealId (reuses its ingredients + plated photo). */
    LOG_SAVED_MEAL,

    /** Confirm a photographed meal's itemized proposal (create foods + entries). */
    CONFIRM_MEAL_ITEMS,

    /** Confirm a scanned nutrition-label draft (create food + entry). */
    CONFIRM_LABEL,
}
