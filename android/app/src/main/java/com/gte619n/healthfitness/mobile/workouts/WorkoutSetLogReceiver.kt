package com.gte619n.healthfitness.mobile.workouts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.feature.workouts.session.WorkoutSessionViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * The "Log set" action on the active-workout notification
 * ([WorkoutSessionNotificationContent.SetAction.QuickLog]): logs the current set
 * with the exact defaults the shade showed (weight × reps prefilled by the same
 * [com.gte619n.healthfitness.feature.workouts.session.prefillFor] the logger
 * uses) and starts the prescribed rest — the shade counterpart of checking the
 * set off in the app, no screen needed.
 *
 * Only *non-final* rep sets reach here; the final working set (mandatory RIR
 * pick) and timed holds deep-link into the app instead, so this path never
 * completes the session and always just starts the next rest.
 */
@AndroidEntryPoint
class WorkoutSetLogReceiver : BroadcastReceiver() {

    // Lazy: resolving the repository opens the SQLCipher-backed HfDatabase, which
    // must not run on the main thread — onReceive resolves it on Dispatchers.IO,
    // the same rule WorkoutSessionService follows.
    @Inject lateinit var sessions: dagger.Lazy<WorkoutSessionRepository>

    @Inject lateinit var timers: WorkoutSessionTimers

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_SET) return
        val programId = intent.getStringExtra(EXTRA_PROGRAM_ID)?.takeIf { it.isNotBlank() } ?: return
        val scheduledId = intent.getStringExtra(EXTRA_SCHEDULED_ID)?.takeIf { it.isNotBlank() } ?: return
        val blockId = intent.getStringExtra(EXTRA_BLOCK_ID)?.takeIf { it.isNotBlank() } ?: return
        val orderIndex = intent.getIntExtra(EXTRA_ORDER_INDEX, -1).takeIf { it >= 0 } ?: return
        val expectedCount = intent.getIntExtra(EXTRA_EXPECTED_COUNT, -1)
        val weightLbs = if (intent.hasExtra(EXTRA_WEIGHT)) intent.getDoubleExtra(EXTRA_WEIGHT, 0.0) else null
        val reps = if (intent.hasExtra(EXTRA_REPS)) intent.getIntExtra(EXTRA_REPS, 0) else null

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                logSet(
                    programId, scheduledId,
                    PrescriptionKey(blockId, orderIndex),
                    expectedCount, weightLbs, reps,
                )
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun logSet(
        programId: String,
        scheduledId: String,
        key: PrescriptionKey,
        expectedCount: Int,
        weightLbs: Double?,
        reps: Int?,
    ) {
        val repo = withContext(Dispatchers.IO) { sessions.get() }
        val draft = repo.peekDraft(programId, scheduledId) ?: return
        val prescription = draft.prescriptionAt(key) ?: return
        val logged = draft.logged[key].orEmpty()
        // Guard against a stale notification tap: if the draft has moved on since
        // the button was built (logged elsewhere, already this set), do nothing
        // rather than double-log or append past the prescription.
        if (expectedCount >= 0 && logged.size != expectedCount) return
        if (logged.size >= (prescription.sets ?: 1)) return

        val at = Instant.now()
        val set = LoggedSet(
            weightLbs = weightLbs,
            reps = reps,
            restSeconds = restSecondsBefore(draft, at),
            completedAt = at,
        )
        repo.updateSets(programId, scheduledId, key, logged + set).onSuccess {
            // Start the prescribed rest, exactly like the in-app logger. A
            // quick-log is never the final set, so it never completes the session.
            prescription.restSeconds?.let { timers.startRest(it, at) }
        }
    }

    /** The prescription at [key] in the draft's snapshot, or null if it's gone. */
    private fun WorkoutSessionDraft.prescriptionAt(key: PrescriptionKey): Prescription? =
        scheduled.session?.blocks
            ?.firstOrNull { it.blockId == key.blockId }
            ?.prescriptions
            ?.firstOrNull { it.orderIndex == key.orderIndex }

    /**
     * The actual rest taken before this set — the gap since the last logged set,
     * clamped to a real between-sets rest. Mirrors the logger's own capture so the
     * shade-logged set records the same [LoggedSet.restSeconds] the app would.
     */
    private fun restSecondsBefore(draft: WorkoutSessionDraft, at: Instant): Int? {
        val lastAt = draft.logged.values.flatten()
            .mapNotNull { it.completedAt }
            .maxOrNull()
            ?: return null
        val seconds = Duration.between(lastAt, at).seconds
        return if (seconds in 1..WorkoutSessionViewModel.MAX_TRACKED_REST_SECONDS) seconds.toInt() else null
    }

    companion object {
        const val ACTION_LOG_SET = "com.gte619n.healthfitness.WORKOUT_LOG_SET"
        const val EXTRA_PROGRAM_ID = "com.gte619n.healthfitness.WORKOUT_PROGRAM_ID"
        const val EXTRA_SCHEDULED_ID = "com.gte619n.healthfitness.WORKOUT_SCHEDULED_ID"
        const val EXTRA_BLOCK_ID = "com.gte619n.healthfitness.WORKOUT_BLOCK_ID"
        const val EXTRA_ORDER_INDEX = "com.gte619n.healthfitness.WORKOUT_ORDER_INDEX"
        const val EXTRA_EXPECTED_COUNT = "com.gte619n.healthfitness.WORKOUT_EXPECTED_COUNT"
        const val EXTRA_WEIGHT = "com.gte619n.healthfitness.WORKOUT_WEIGHT"
        const val EXTRA_REPS = "com.gte619n.healthfitness.WORKOUT_REPS"
    }
}
