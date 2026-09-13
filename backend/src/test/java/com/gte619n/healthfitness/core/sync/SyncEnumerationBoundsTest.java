package com.gte619n.healthfitness.core.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the pure cursor→bound derivations behind the PERF-001
 * parent-enumeration pruning. No Firestore involved.
 */
class SyncEnumerationBoundsTest {

    private static SyncCursor cursorAt(Instant when) {
        return new SyncCursor(when.toEpochMilli(), "nutritionDays/entries", "2026-01-01/e1");
    }

    @Test
    void nullCursorHasNoDateFloorAndAlwaysFullScans() {
        assertThat(SyncEnumerationBounds.dateFloorForCursor(null)).isNull();
        assertThat(SyncEnumerationBounds.isFullScanSync(null)).isTrue();
    }

    @Test
    void dateFloorIsCursorDateMinusSlackAsIsoString() {
        LocalDate cursorDate = LocalDate.of(2026, 9, 13);
        Instant cursorInstant = cursorDate.atStartOfDay(ZoneOffset.UTC).toInstant();

        String floor = SyncEnumerationBounds.dateFloorForCursor(cursorAt(cursorInstant));

        LocalDate expected = cursorDate.minusDays(SyncEnumerationBounds.BACKDATE_SLACK_DAYS);
        assertThat(floor).isEqualTo(expected.toString());
        // Sanity: the floor sorts (lexicographically == chronologically) at or
        // before the cursor's own day, so today's edit is always in range.
        assertThat(floor.compareTo(cursorDate.toString())).isLessThanOrEqualTo(0);
    }

    @Test
    void floorStaysWithinSlackAcrossAMultiYearAccount() {
        // A 2-year-old cursor still only floors 35 days back from ITS date —
        // this is what stops the enumeration growing with account age.
        Instant twoYears = LocalDate.of(2028, 9, 13)
            .atStartOfDay(ZoneOffset.UTC).toInstant();
        String floor = SyncEnumerationBounds.dateFloorForCursor(cursorAt(twoYears));
        assertThat(floor).isEqualTo("2028-08-09"); // 2028-09-13 minus 35 days
    }

    @Test
    void fullScanFiresPeriodicallyForDeltaCursors() {
        // Scan a window of consecutive seconds; the safety valve must fire at
        // least once every FULL_SCAN_EVERY_N so far-backdated edits converge,
        // and must NOT fire on every sync (else pruning buys nothing).
        int n = SyncEnumerationBounds.FULL_SCAN_EVERY_N;
        int fulls = 0;
        long baseSecond = 1_700_000_000L; // arbitrary epoch second
        for (int i = 0; i < n; i++) {
            Instant when = Instant.ofEpochSecond(baseSecond + i);
            if (SyncEnumerationBounds.isFullScanSync(cursorAt(when))) {
                fulls++;
            }
        }
        assertThat(fulls)
            .as("exactly one full scan per N consecutive-second cursors")
            .isEqualTo(1);
    }

    @Test
    void subSecondCursorDriftDoesNotChangeFullScanDecision() {
        // The decision is keyed on whole seconds, so millisecond jitter within
        // the same second is stable (no accidental flip-flop between pages).
        long second = 1_700_000_016L; // a multiple of 16 → full scan
        boolean atZeroMs = SyncEnumerationBounds.isFullScanSync(
            cursorAt(Instant.ofEpochMilli(second * 1000L)));
        boolean at999Ms = SyncEnumerationBounds.isFullScanSync(
            cursorAt(Instant.ofEpochMilli(second * 1000L + 999L)));
        assertThat(atZeroMs).isEqualTo(at999Ms);
    }
}
