package com.gte619n.healthfitness.data.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * IMPL-AND-20 (Phase 4) — WorkManager triggers (D10).
 *
 * Three workers, all Hilt-injected via [HiltWorker] + the app's
 * `HiltWorkerFactory`:
 *  - [SyncWorker] — expedited delta pull. Phase 6's FirebaseMessagingService
 *    enqueues this on an FCM "sync" data message; also used for foreground pulls.
 *  - [PeriodicSyncWorker] — ~6h network-required floor so data stays fresh even
 *    when FCM never arrives (non-Play devices).
 *  - [OutboxDrainWorker] — drains the outbox; enqueued on connectivity-regained
 *    and after local writes. Also runs a pull so a reconnect both pushes and pulls.
 *
 * [SyncScheduler] registers the periodic floor + the connectivity-regained
 * trigger and exposes one-shot enqueue helpers.
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        syncEngine.pull()
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        const val NAME = "hf-sync-pull"
    }
}

@HiltWorker
class PeriodicSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val syncEngine: SyncEngine,
    private val outbox: OutboxRepository,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        outbox.drain()
        syncEngine.pull()
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        const val NAME = "hf-sync-periodic"
    }
}

@HiltWorker
class OutboxDrainWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val outbox: OutboxRepository,
    private val syncEngine: SyncEngine,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        outbox.drain()
        // A reconnect should also pull anything we missed while offline.
        syncEngine.pull()
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        const val NAME = "hf-outbox-drain"
    }
}

/**
 * Registers and triggers the sync workers. Call [registerPeriodic] once at app
 * start (Phase 6 wires it into the Application / first-sign-in); [enqueuePull],
 * [enqueueDrain] are the one-shot triggers used by FCM (Phase 6) and the
 * after-write / connectivity-regained paths.
 */
class SyncScheduler(private val workManager: WorkManager) {

    private val connectedConstraint =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** ~6h network-required floor (D10). KEEP so re-registration is idempotent. */
    fun registerPeriodic() {
        val request = PeriodicWorkRequestBuilder<PeriodicSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(connectedConstraint)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PeriodicSyncWorker.NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Expedited delta pull (FCM data message / foreground). */
    fun enqueuePull() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(connectedConstraint)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(SyncWorker.NAME, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Drain the outbox + pull. The CONNECTED constraint makes WorkManager run
     * this automatically when connectivity is regained if it was enqueued while
     * offline (the connectivity-regained trigger, D10).
     */
    fun enqueueDrain() {
        val request = OneTimeWorkRequestBuilder<OutboxDrainWorker>()
            .setConstraints(connectedConstraint)
            .build()
        workManager.enqueueUniqueWork(OutboxDrainWorker.NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }
}
