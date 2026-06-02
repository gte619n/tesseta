package com.gte619n.healthfitness.core.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** Unit tests for the recent-window emission filter (IMPL-AND-20 #37 / D14). */
class SyncRecentWindowTest {

    private static final LocalDate BOUND = LocalDate.parse("2026-05-19");
    private final SyncRecentWindow window = new SyncRecentWindow(BOUND);

    @Test
    void crudDomainsAlwaysIncludedRegardlessOfDate() {
        assertThat(window.isHeavy("medications")).isFalse();
        assertThat(window.includes("medications", null)).isTrue();
        assertThat(window.includes("goals", LocalDate.parse("2000-01-01"))).isTrue();
        assertThat(window.includes("locations", null)).isTrue();
    }

    @Test
    void heavyDocsBoundedByDate() {
        assertThat(window.isHeavy("bloodReadings")).isTrue();
        // On/after the bound ⇒ included; before ⇒ excluded.
        assertThat(window.includes("bloodReadings", BOUND)).isTrue();
        assertThat(window.includes("bloodReadings", BOUND.plusDays(1))).isTrue();
        assertThat(window.includes("bloodReadings", BOUND.minusDays(1))).isFalse();
        assertThat(window.includes("dailyMetrics", LocalDate.parse("2026-01-01"))).isFalse();
        assertThat(window.includes("nutritionDays/entries", LocalDate.parse("2026-05-30"))).isTrue();
    }

    @Test
    void heavyDocWithUnreadableDateIsConservativelyIncluded() {
        // Never hide a heavy row whose date we couldn't resolve.
        assertThat(window.includes("bloodReadings", null)).isTrue();
    }

    @Test
    void allSixHeavyCollectionsAreRecognised() {
        assertThat(SyncRecentWindow.heavyCollections()).containsExactlyInAnyOrder(
            "bloodReadings", "bodyComposition", "dailyMetrics",
            "nutritionDailyLogs", "nutritionDays/entries", "weeklyWorkoutAggregates");
    }

    @Test
    void nullSinceRejected() {
        assertThatThrownBy(() -> new SyncRecentWindow(null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
