package com.gte619n.healthfitness.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * IMPL-AND-20 (Phase 3) — offline write queue (D7).
 *
 * Each row is one pending mutation. `mutationId` (a client UUID) doubles as the
 * `Idempotency-Key` sent on replay. Drain (Phase 4) collapses mutations per
 * `entityId` via the outbox reducer, then replays survivors in `seq` order,
 * backing off via `nextAttemptAt` on failure.
 *
 * Indexed on `entityId` (per-entity ordering + collapse) and `nextAttemptAt`
 * (the "due now" query the drain worker runs).
 */
@Entity(
    tableName = "outbox",
    indices = [Index("entityId"), Index("nextAttemptAt")],
)
data class OutboxEntity(
    @PrimaryKey val mutationId: String,
    val entityTable: String,
    val entityId: String,
    val op: String,
    val payloadJson: String?,
    val originDeviceId: String,
    val seq: Long,
    val attempts: Int,
    val nextAttemptAt: Long,
    val createdAt: Long,
)

/** Mutation operation kinds queued in the outbox (D7). */
enum class OutboxOp { CREATE, UPDATE, DELETE }
