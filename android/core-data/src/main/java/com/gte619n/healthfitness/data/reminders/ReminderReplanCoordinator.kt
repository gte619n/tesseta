package com.gte619n.healthfitness.data.reminders

import com.gte619n.healthfitness.data.db.dao.MedicationDao
import com.gte619n.healthfitness.data.sync.SyncSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the medication-reminder alarm chain in lock-step with the data layer so
 * reminders fire without waiting for an app restart or the ~12h safety-net
 * worker (IMPL-STAB Workstream F, items 1 & 2):
 *
 *  1. **Medication-mirror changes** — observes the `medications` Room Flow.
 *     Every create / update / discontinue / dose-change / delete lands in that
 *     mirror (optimistically and offline), so collecting it and re-planning
 *     (debounced, to coalesce a burst of writes) re-arms the alarm the moment a
 *     med changes. Offline-safe: [ReminderEngine.replan] reads only the cached
 *     settings + the local mirror, never the network.
 *  2. **Multi-device sync pushes** — observes [SyncSignals.pushes] and re-plans
 *     when a push carries the `medications` or `medicationReminderSettings`
 *     collection hint (or no hint at all), so a change made on another device
 *     re-arms here as soon as the silent FCM wakeup arrives.
 *
 * [start] is idempotent and called once from `HealthFitnessApp.onCreate`. The
 * coordinator owns an application-lifetime scope; there is nothing to tear down.
 */
@Singleton
class ReminderReplanCoordinator internal constructor(
    private val medicationDao: MedicationDao,
    private val syncSignals: SyncSignals,
    private val scope: CoroutineScope,
    /** The replan action — the engine in production; a probe in tests. */
    private val replan: suspend () -> Unit,
) {
    @Inject
    constructor(
        engine: ReminderEngine,
        medicationDao: MedicationDao,
        syncSignals: SyncSignals,
    ) : this(
        medicationDao = medicationDao,
        syncSignals = syncSignals,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        replan = { engine.replan() },
    )

    @Volatile private var started = false

    /** Collection hints that affect the reminder schedule. */
    private val reminderTags = setOf("medications", "medicationReminderSettings")

    @OptIn(FlowPreview::class)
    @Synchronized
    fun start() {
        if (started) return
        started = true

        // 1. Re-plan on any change to the medication mirror. drop(1) skips the
        // initial replay emission — app start already calls replan() directly —
        // so this only reacts to genuine subsequent changes. distinctUntilChanged
        // on a cheap fingerprint avoids re-planning on no-op refreshes.
        medicationDao.observeActive()
            .map { rows -> rows.map { it.id to it.lastUpdate }.toSet() }
            .distinctUntilChanged()
            .drop(1)
            .debounce(DEBOUNCE_MILLIS)
            .onEach { runCatching { replan() } }
            .launchIn(scope)

        // 2. Re-plan when a sync push touches medications or reminder settings.
        // A null hint (collections absent) is treated as "could be relevant".
        syncSignals.pushes
            .filter { hint -> hint == null || reminderTags.any { hint.contains(it) } }
            .onEach { runCatching { replan() } }
            .launchIn(scope)
    }

    internal companion object {
        const val DEBOUNCE_MILLIS = 750L
    }
}
