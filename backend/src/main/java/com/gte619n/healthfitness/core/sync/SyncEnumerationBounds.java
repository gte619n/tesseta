package com.gte619n.healthfitness.core.sync;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Pure cursor→bound derivations for the delta reader's parent-enumeration
 * pruning (PERF-001). Kept free of Firestore so the arithmetic is unit-testable.
 *
 * <p><b>Why this exists.</b> {@code FirestoreSyncChangeReader} used to enumerate
 * <em>every</em> date-keyed parent doc a user owns (one {@code nutritionDays}
 * doc per calendar day, forever) on every sync page, issuing one leaf query per
 * parent even when the cursor makes them all return nothing. Since a
 * {@code nutritionDays} doc id is the ISO date of the day it holds and an
 * entry's {@code updatedAt} only ever moves forward, a delta sync with cursor
 * {@code T} only needs to look at day-docs on or after a floor derived from
 * {@code T} — with a backdate-slack window so edits to recent past days
 * (the app lets you log/adjust yesterday's dinner) are still caught.
 *
 * <p>The floor is a <em>pruning heuristic</em>, never a correctness boundary:
 * the low-frequency {@link #isFullScanSync} fallback re-enumerates everything so
 * a backdated edit older than the slack still converges on a later sync.
 */
public final class SyncEnumerationBounds {

    /**
     * How many days before the cursor's date the {@code nutritionDays}
     * enumeration floor is set, to cover backdated edits to recent past days.
     *
     * <p>DECISION: no explicit max-backdate limit exists in the backend
     * (nutrition entries accept any {@code LocalDate}; there is no server-side
     * "you may only edit the last N days" rule), so this is a conservatively
     * generous slack rather than a discovered constant. 35 days comfortably
     * covers a month-plus of "fix last week's log" edits between two syncs of an
     * active device; anything older than this is caught by the periodic
     * full-scan fallback ({@link #isFullScanSync}). Widen if backdated-edit
     * misses are ever reported (correctness is preserved by the fallback either
     * way; a wider slack only trades a few more reads for faster convergence).
     */
    public static final int BACKDATE_SLACK_DAYS = 35;

    /**
     * Every Nth delta sync ignores the date floor and does a full parent
     * enumeration, so a backdated edit older than {@link #BACKDATE_SLACK_DAYS}
     * still converges without a client-visible miss. This is the safety valve
     * that lets the floor be an aggressive pruning heuristic. There is no
     * per-user sync counter server-side, so "every Nth" is approximated
     * deterministically from the cursor timestamp ({@link #isFullScanSync}).
     */
    public static final int FULL_SCAN_EVERY_N = 16;

    private SyncEnumerationBounds() {
    }

    /**
     * The inclusive lower bound (as an ISO {@code yyyy-MM-dd} string, which sorts
     * identically to the date) for {@code nutritionDays} doc ids to enumerate
     * given a delta cursor. {@code null} cursor → {@code null} (enumerate all:
     * a first sync is already payload-capped by the recent window).
     *
     * @param since the delta cursor, or {@code null} for an initial full sync
     * @return the floor day id (inclusive), or {@code null} to enumerate all
     */
    public static String dateFloorForCursor(SyncCursor since) {
        if (since == null) {
            return null;
        }
        LocalDate cursorDate = Instant.ofEpochMilli(since.lastUpdateMillis())
            .atZone(ZoneOffset.UTC)
            .toLocalDate();
        return cursorDate.minusDays(BACKDATE_SLACK_DAYS).toString();
    }

    /**
     * Whether this sync should bypass the date-floor pruning and fully enumerate
     * every parent (the periodic safety valve). Always {@code true} for a null
     * cursor (initial sync). For a delta sync it fires deterministically ~1 in
     * {@link #FULL_SCAN_EVERY_N} based on the cursor's millisecond timestamp, so
     * a device that keeps syncing will periodically pick up any far-backdated
     * edit the floor skipped.
     */
    public static boolean isFullScanSync(SyncCursor since) {
        if (since == null) {
            return true;
        }
        // Derive a stable pseudo-index from the cursor timestamp. Successive
        // cursors advance as data changes, so this rotates through the residues
        // over a run of syncs rather than sticking on one value.
        long seconds = Math.floorDiv(since.lastUpdateMillis(), 1000L);
        return Math.floorMod(seconds, (long) FULL_SCAN_EVERY_N) == 0L;
    }
}
