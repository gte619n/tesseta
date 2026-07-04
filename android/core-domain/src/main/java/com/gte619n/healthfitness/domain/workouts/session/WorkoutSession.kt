package com.gte619n.healthfitness.domain.workouts.session

import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
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
