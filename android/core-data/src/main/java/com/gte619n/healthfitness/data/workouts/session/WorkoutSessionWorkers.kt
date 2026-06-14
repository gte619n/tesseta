package com.gte619n.healthfitness.data.workouts.session

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-0012 Decision 4 — the stale-draft sweep triggers.
 *
 * A draft idle for more than 24h is finalized as COMPLETED (≥1 logged set) or
 * discarded (zero sets) by [WorkoutSessionRepository.finalizeStaleDrafts]. The
 * sweep runs from two places, per the decision log (D7):
 *  - **app open** — [WorkoutSessionBootstrap.onAppStart], wired into the
 *    signed-in entry next to the IMPL-AND-20 first-sync gate;
 *  - **periodically** — [StaleDraftWorker], a [SWEEP_INTERVAL_HOURS]-hourly
 *    WorkManager job so an abandoned session finalizes even if the app is
 *    never reopened.
 *
 * Neither needs a network constraint: the finalize path enqueues the
 * completion upsert into the offline outbox, which already owns
 * connectivity-aware replay.
 */
@HiltWorker
class StaleDraftWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sessions: WorkoutSessionRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        sessions.finalizeStaleDrafts().getOrThrow()
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        const val NAME = "hf-session-stale-drafts"

        /** Sweep cadence — same floor as the periodic sync (D10). */
        const val SWEEP_INTERVAL_HOURS = 6L
    }
}

/**
 * App-start hook for the stale-draft sweep: registers the periodic worker
 * (idempotent KEEP) and runs one immediate pass. Resolve lazily off the main
 * thread — like FirstSyncGate, this transitively opens the SQLCipher store.
 */
@Singleton
class WorkoutSessionBootstrap @Inject constructor(
    private val sessions: WorkoutSessionRepository,
    private val workManager: WorkManager,
) {
    suspend fun onAppStart() {
        registerPeriodic()
        sessions.finalizeStaleDrafts()
    }

    /** Register the periodic sweep. KEEP so re-registration is idempotent. */
    fun registerPeriodic() {
        val request = PeriodicWorkRequestBuilder<StaleDraftWorker>(
            StaleDraftWorker.SWEEP_INTERVAL_HOURS,
            TimeUnit.HOURS,
        ).build()
        workManager.enqueueUniquePeriodicWork(
            StaleDraftWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
