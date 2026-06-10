package com.gte619n.healthfitness.domain.workouts.session

import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// ADR-0012 (IMPL-AND-16) — the device-local active workout session. The
// in-progress session is a phone-owned draft persisted in the encrypted Room
// store (it survives process death and works with zero connectivity); the
// backend learns about a session only when it is finished or skipped, via one
// idempotent completion upsert routed through the offline outbox.

/**
 * Identifies one [com.gte619n.healthfitness.domain.workouts.program.Prescription]
 * inside the session snapshot. Prescriptions have no id, so logged sets key by
 * `(blockId, orderIndex)` against the snapshot (IMPL-16 D2).
 */
data class PrescriptionKey(val blockId: String, val orderIndex: Int)

/**
 * Lifecycle state of a local draft. A draft is [ACTIVE] from start until it is
 * finished, skipped, or discarded — all three remove the row (the completion
 * upload, if any, lives in the outbox from that point on), so no terminal
 * status is ever persisted.
 */
enum class DraftStatus { ACTIVE }

/**
 * One in-progress (or stale, not-yet-finalized) workout session on this device.
 *
 * [scheduled] is the snapshot taken when the session started, so the logger
 * keeps rendering the same prescriptions even if the program is edited or the
 * mirror is refreshed mid-session.
 */
data class WorkoutSessionDraft(
    val programId: String,
    val scheduledId: String,
    val startedAt: Instant,
    val lastActivityAt: Instant,
    val status: DraftStatus,
    val scheduled: ScheduledWorkout,
    /** Performed sets so far, keyed by prescription. */
    val logged: Map<PrescriptionKey, List<LoggedSet>>,
) {
    val totalLoggedSets: Int
        get() = logged.values.sumOf { it.size }
}

/**
 * Draft lifecycle for the phone workout logger (ADR-0012 Decisions 1–4).
 *
 * Start/resume, set updates, and discard are purely local; finish and skip
 * additionally enqueue the idempotent completion upsert
 * (`PUT /api/me/workout-programs/{programId}/sessions/{scheduledId}`) through
 * the offline outbox, so they succeed instantly offline and replay safely.
 */
interface WorkoutSessionRepository {
    /** Reactive draft for one scheduled session (null when none exists). */
    fun observeDraft(programId: String, scheduledId: String): Flow<WorkoutSessionDraft?>

    /** Every local draft, newest started first (resume affordances). */
    fun observeDrafts(): Flow<List<WorkoutSessionDraft>>

    /**
     * Start a session from the locally-mirrored scheduled workout, snapshotting
     * it into the draft. Resumes (returns) the existing draft if one is already
     * in flight for this key.
     */
    suspend fun start(programId: String, scheduledId: String): Result<WorkoutSessionDraft>

    /**
     * Replace the logged sets for one prescription (empty list removes the
     * entry). Bumps the draft's `lastActivityAt`.
     */
    suspend fun updateSets(
        programId: String,
        scheduledId: String,
        key: PrescriptionKey,
        sets: List<LoggedSet>,
    ): Result<WorkoutSessionDraft>

    /**
     * Finish the session: upload `COMPLETED` with all logged actuals
     * (`durationSeconds` = start → now) and delete the draft.
     */
    suspend fun finish(programId: String, scheduledId: String): Result<Unit>

    /** Skip the session: upload `SKIPPED` (clears actuals) and delete any draft. */
    suspend fun skip(programId: String, scheduledId: String): Result<Unit>

    /** Throw the draft away locally; nothing reaches the backend. */
    suspend fun discard(programId: String, scheduledId: String): Result<Unit>

    /**
     * ADR-0012 Decision 4 — finalize abandoned drafts. A draft idle for more
     * than 24h with at least one logged set is uploaded as `COMPLETED` (with
     * `durationSeconds` = start → last set's `completedAt`); a stale draft with
     * zero sets is deleted. Invoked on app open and by the periodic worker.
     */
    suspend fun finalizeStaleDrafts(): Result<StaleDraftResult>

    /** Outcome counts of one [finalizeStaleDrafts] pass. */
    data class StaleDraftResult(val finalized: Int, val discarded: Int)
}
