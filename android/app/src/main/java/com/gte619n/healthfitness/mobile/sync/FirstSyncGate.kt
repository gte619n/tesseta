package com.gte619n.healthfitness.mobile.sync

import com.gte619n.healthfitness.data.db.dao.SyncStateDao
import com.gte619n.healthfitness.data.sync.SyncEngine
import com.gte619n.healthfitness.data.sync.SyncFlags
import com.gte619n.healthfitness.data.sync.SyncScheduler
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 6) — first-run sync gate (D14).
 *
 * On the **first** sign-in (no prior full sync recorded in `sync_state`), the UI
 * must wait on a brief, bounded initial pull before rendering the dashboard, so a
 * cold install never shows an empty app. A **returning** user (who already has a
 * `lastFullSyncAt`) is NOT gated — their Room mirror already has data and the
 * normal foreground/periodic/FCM triggers keep it fresh.
 *
 * Flow:
 *  1. [needsFirstSync] reads `sync_state.lastFullSyncAt`. Null ⇒ first run.
 *     (Kill-switch on ⇒ the client is in live-network mode and Room is not the
 *     source of truth, so there is nothing to gate — treat as "no gate".)
 *  2. [runInitialSync] performs a **date-windowed** pull
 *     (`recentSince = now - `[RECENT_WINDOW_DAYS]` days`) so the CRUD domains land
 *     in full and the heavy time-series land only for the recent window, then
 *     releases the UI fast.
 *  3. [scheduleBackfill] enqueues an unbounded backfill (`recentSince = null`) that
 *     continues the SAME persisted cursor — picking up the older historical
 *     time-series the window omitted, with no skip/dup — plus the periodic floor.
 *
 * ### 14-day windowing (D14, #37) — true server-side window
 * `GET /api/me/sync` now accepts a `recentSince` lower bound (the parallel backend
 * agent's #37 work): heavy time-series collections only return docs whose record
 * date is ≥ `recentSince`, while the CRUD domains are always returned in full. The
 * gate sends `now - 14d` for the first window and `null` for the unbounded
 * backfill, replacing the prior client-side page-budget approximation. The cursor
 * is shared across both passes (persisted after every page in [SyncEngine.pull]),
 * so the backfill resumes strictly after the windowed pull with no duplicates.
 *
 * Contract assumption: the client wires against the param name **`recentSince`**
 * (ISO-8601 instant). If the server has not yet deployed the param it simply
 * ignores the unknown query value and returns the full enumeration — the gate
 * still releases the UI correctly, just not date-bounded.
 */
@Singleton
class FirstSyncGate @Inject constructor(
    private val syncStateDao: SyncStateDao,
    private val syncEngine: SyncEngine,
    private val scheduler: SyncScheduler,
    private val syncFlags: SyncFlags,
) {

    /** True ⇒ a fresh sign-in that must block on [runInitialSync] before the UI. */
    suspend fun needsFirstSync(): Boolean {
        // In live-network (kill-switch) mode Room is not the source of truth, so
        // there is no first-sync to gate on.
        if (syncFlags.isKillSwitchOn()) return false
        return syncStateDao.get()?.lastFullSyncAt == null
    }

    /**
     * Brief blocking initial sync (D14, #37): a **date-windowed** pull bounding the
     * heavy time-series to the last [RECENT_WINDOW_DAYS] days (CRUD in full) so the
     * UI is released quickly. Best-effort — a failure still releases the UI (the
     * user lands on a possibly-empty dashboard the background triggers will fill);
     * it must never wedge the app on a cold sign-in.
     */
    suspend fun runInitialSync() {
        runCatching { syncEngine.pull(recentSince = recentWindowSince()) }
    }

    /**
     * Register the periodic floor and kick the **unbounded backfill** that drains
     * whatever the windowed initial sync left (`recentSince = null`), continuing the
     * same persisted cursor (no skip/dup) so the older historical time-series fill
     * in lazily without waiting up to ~6h for the floor. Safe every launch
     * (idempotent KEEP policy).
     */
    fun scheduleBackfill() {
        scheduler.registerPeriodic()
        scheduler.enqueuePull()
    }

    /** ISO-8601 lower bound for the first window: `now - `[RECENT_WINDOW_DAYS]` days`. */
    private fun recentWindowSince(): String =
        Instant.now().minus(RECENT_WINDOW_DAYS.toLong(), ChronoUnit.DAYS).toString()

    companion object {
        /** The D14 heavy-series recent window for the first blocking sync. */
        const val RECENT_WINDOW_DAYS = 14
    }
}
