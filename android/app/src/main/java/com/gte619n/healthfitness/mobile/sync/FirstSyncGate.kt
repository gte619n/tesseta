package com.gte619n.healthfitness.mobile.sync

import com.gte619n.healthfitness.data.db.dao.SyncStateDao
import com.gte619n.healthfitness.data.sync.SyncEngine
import com.gte619n.healthfitness.data.sync.SyncFlags
import com.gte619n.healthfitness.data.sync.SyncScheduler
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
 *  2. [runInitialSync] performs a **bounded** pull ([INITIAL_PAGE_BUDGET] pages)
 *     so the CRUD domains and the recent heavy time-series land fast, then
 *     releases the UI.
 *  3. [scheduleBackfill] enqueues the periodic floor; its first (unbounded) pull
 *     drains the remaining `hasMore` pages — the older historical time-series —
 *     lazily in the background.
 *
 * ### 14-day windowing (D14) — client-side limitation, documented
 * The delta API is cursor-ordered by `lastUpdate`, not by record date, and the
 * cursor is opaque, so the client cannot ask the server for "only the last 14
 * days of heavy series" without server support. We approximate the intent with a
 * **page budget**: the bounded initial pull releases the UI quickly with the most
 * recently-updated docs, and the remainder backfills lazily. A true date-windowed
 * first sync (`since=14d`) would need a server-side hint on `GET /api/me/sync`;
 * flagged in the outstanding questions.
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
     * Brief blocking initial sync (D14). Bounded so the UI is released quickly;
     * best-effort — a failure still releases the UI (the user lands on a possibly
     * empty dashboard that the background triggers will fill), it must never wedge
     * the app on a cold sign-in.
     */
    suspend fun runInitialSync() {
        runCatching { syncEngine.pull(maxPages = INITIAL_PAGE_BUDGET) }
    }

    /**
     * Register the periodic floor so the remaining history backfills lazily after
     * the gate releases. Safe to call every launch (idempotent KEEP policy).
     */
    fun scheduleBackfill() {
        scheduler.registerPeriodic()
        // Kick a one-shot unbounded pull to drain whatever the bounded initial
        // sync left as `hasMore` work, without waiting up to ~6h for the floor.
        scheduler.enqueuePull()
    }

    companion object {
        /**
         * Page budget for the bounded first sync. At the server's default 500
         * docs/page this is up to 1500 of the most-recently-updated docs before
         * the UI is released — comfortably covers the CRUD domains + recent
         * time-series for a typical user, with older history backfilling after.
         */
        const val INITIAL_PAGE_BUDGET = 3
    }
}
