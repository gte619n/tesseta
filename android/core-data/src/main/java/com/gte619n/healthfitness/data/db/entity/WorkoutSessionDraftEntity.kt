package com.gte619n.healthfitness.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * ADR-0012 (IMPL-AND-16) — the device-local in-progress workout session.
 *
 * This is **not** a mirror table: the draft never exists on the backend (the
 * server only sees the completion upsert), so it carries no MirrorRow
 * dirty/syncState bookkeeping. It lives in the same SQLCipher-encrypted store
 * so an in-flight session survives process death and is wiped on sign-out.
 *
 * Keyed by `(programId, scheduledId)` — one draft per scheduled session.
 * Following the established idiom of opaque Moshi-JSON columns (payloadJson on
 * the mirror tables), the session snapshot and the logged sets are stored as
 * JSON encoded by the repository, not via Room TypeConverters:
 *  - [sessionJson] — the `ScheduledWorkoutDto` snapshot taken at start, so the
 *    logger keeps rendering the same prescriptions even if the program is
 *    edited mid-session.
 *  - [loggedJson]  — a `List<LoggedPrescriptionDto>` (sets keyed by
 *    `(blockId, orderIndex)`, IMPL-16 D2), already in the completion-request
 *    wire shape.
 *
 * `lastActivityAt` is indexed: the stale-draft sweep (ADR-0012 Decision 4)
 * queries drafts idle past the 24h cutoff.
 */
@Entity(
    tableName = "workoutSessionDrafts",
    primaryKeys = ["programId", "scheduledId"],
    indices = [Index("lastActivityAt")],
)
data class WorkoutSessionDraftEntity(
    val programId: String,
    val scheduledId: String,
    /** Epoch millis the session was started on this device. */
    val startedAt: Long,
    /** Epoch millis of the most recent draft mutation (start counts). */
    val lastActivityAt: Long,
    /** [com.gte619n.healthfitness.domain.workouts.session.DraftStatus] name. */
    val status: String,
    /** Moshi `ScheduledWorkoutDto` snapshot taken at start. */
    val sessionJson: String,
    /** Moshi `List<LoggedPrescriptionDto>` of the sets logged so far. */
    val loggedJson: String,
)
