package com.gte619n.healthfitness.domain.workouts.session

import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import kotlinx.coroutines.flow.Flow
import java.time.Instant

// ADR-0012 (IMPL-AND-17) — the device-local active workout session. The
// in-progress session is a phone-owned draft persisted in the encrypted Room
// store (it survives process death and works with zero connectivity); the
// backend learns about a session only when it is finished or skipped, via one
// idempotent completion upsert routed through the offline outbox.

/**
 * Identifies one [com.gte619n.healthfitness.domain.workouts.program.Prescription]
 * inside the session snapshot. Prescriptions have no id, so logged sets key by
 * `(blockId, orderIndex)` against the snapshot (IMPL-17 D2).
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
 * A finished/skipped session whose completion upload the server terminally
 * rejected (IMPL-17 A10): the outbox row was parked instead of retried, so the
 * outcome exists only on this device. Surfaced so the user can restore it into
 * the logger ([WorkoutSessionRepository.restoreParked]) and re-finish against
 * the current plan, instead of being stuck behind the blind manual retry.
 */
data class ParkedCompletion(
    val programId: String,
    val scheduledId: String,
    /** The rejected outcome (`COMPLETED` or `SKIPPED`). */
    val status: ScheduledStatus,
    val completedAt: Instant?,
    /** Total sets carried by the parked wire payload. */
    val loggedSetCount: Int,
    /**
     * Sets whose `(blockId, orderIndex)` no longer exists in the CURRENT local
     * snapshot (the plan was rewritten under the upload). They cannot be
     * restored — the restore confirmation surfaces this count so nothing is
     * dropped silently.
     */
    val orphanedSetCount: Int,
    /**
     * False when the scheduled session is no longer mirrored locally (or lost
     * its day snapshot) — there is nothing to restore against, so the only
     * recovery is [WorkoutSessionRepository.discardParked].
     */
    val sessionAvailable: Boolean,
    /** Day label from the current snapshot, when available. */
    val dayLabel: String?,
)

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

    /**
     * Delete a logged session from history by reverting it to `PLANNED`: uploads
     * the un-log (which on the backend clears the actuals, removes the fanned-out
     * workout, and recomputes the week) and clears the local mirror's outcome, so
     * the day drops back to planned and can be run again. Routed through the
     * offline outbox like [skip]; any stale local draft is removed too.
     */
    suspend fun reset(programId: String, scheduledId: String): Result<Unit>

    /**
     * IMPL-COACH — best-effort AI post-workout recap for a just-finished
     * session. Network-only and side-effect-free: returns null when offline,
     * when the completion upsert hasn't replayed server-side yet, or when the
     * coach is unavailable. Never throws — the finish flow shows the numeric
     * summary regardless.
     */
    suspend fun fetchRecap(programId: String, scheduledId: String): String?

    /**
     * IMPL-COACH PR2 — the sets performed the last time each of this session's
     * exercises was done, keyed by exerciseId, so the logger can prefill new
     * sets from the literal previous session. Network-only and best-effort:
     * returns an empty map when offline or on any error (the logger falls back
     * to the designed target).
     */
    suspend fun lastSets(programId: String, scheduledId: String): Map<String, List<LoggedSet>>

    /** Throw the draft away locally; nothing reaches the backend. */
    suspend fun discard(programId: String, scheduledId: String): Result<Unit>

    /**
     * Completion uploads parked on a terminal server rejection (IMPL-17 A10),
     * newest first. Entries with an in-flight draft for the same session are
     * suppressed — the draft is the single owner of that session's UI and its
     * eventual finish supersedes the parked payload in the outbox chain.
     */
    fun observeParkedCompletions(): Flow<List<ParkedCompletion>>

    /**
     * Re-materialize a parked completion as the local draft so the user can
     * re-log against the CURRENT snapshot and re-finish. Logged sets are
     * mapped onto the current snapshot by `(blockId, orderIndex)`; orphaned
     * entries (key no longer in the plan) are dropped — their count was
     * surfaced on [ParkedCompletion.orphanedSetCount] before confirming.
     * `startedAt` derives from the parked outcome (`completedAt −
     * durationSeconds`, falling back to the earliest per-set timestamp, then
     * now). The parked outbox row is deleted: the draft becomes the single
     * owner of the upload again (N4). Fails — leaving the parked row intact —
     * when a draft for the session is already in flight or the session is no
     * longer mirrored locally.
     */
    suspend fun restoreParked(programId: String, scheduledId: String): Result<WorkoutSessionDraft>

    /**
     * Give up on a parked completion: delete the parked outbox row(s) and
     * revert the optimistic local completion so the next pull reconciles.
     * The recovery of last resort when [ParkedCompletion.sessionAvailable]
     * is false.
     */
    suspend fun discardParked(programId: String, scheduledId: String): Result<Unit>

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
