package com.gte619n.healthfitness.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class V1ParamsTest {

    @Test
    void limitDefaultsAndClampsToMax() {
        assertThat(V1Params.limit(null)).isEqualTo(V1Params.DEFAULT_LIMIT);
        assertThat(V1Params.limit(10)).isEqualTo(10);
        assertThat(V1Params.limit(9999)).isEqualTo(V1Params.MAX_LIMIT);
    }

    @Test
    void limitRejectsNonPositive() {
        assertThatThrownBy(() -> V1Params.limit(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesInstantsAndDates() {
        assertThat(V1Params.instant("2026-07-01T00:00:00Z"))
            .isEqualTo(Instant.parse("2026-07-01T00:00:00Z"));
        assertThat(V1Params.instant(null)).isNull();
        assertThat(V1Params.date("2026-07-01")).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(V1Params.date("")).isNull();
    }

    @Test
    void rejectsMalformedInstantAndDate() {
        assertThatThrownBy(() -> V1Params.instant("yesterday"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V1Params.date("07/01/2026"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void dateRangeDefaultsToTrailingWindow() {
        V1Params.DateRange range = V1Params.dateRange(null, null, 30, 366);
        assertThat(range.to()).isEqualTo(LocalDate.now());
        assertThat(range.from()).isEqualTo(LocalDate.now().minusDays(30));
    }

    @Test
    void dateRangeRejectsInvertedAndTooWide() {
        assertThatThrownBy(() -> V1Params.dateRange("2026-07-10", "2026-07-01", 30, 366))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> V1Params.dateRange("2020-01-01", "2026-01-01", 30, 366))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
