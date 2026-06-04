package com.gte619n.healthfitness.core.metric;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DailyMetricFieldsTest {

    @Test
    void record_roundTrips_allFields_includingHrvAndSleepScore() {
        Instant now = Instant.now();
        DailyMetric metric = new DailyMetric(
            "user-1",
            LocalDate.of(2026, 5, 27),
            8500,
            58,
            420,
            72,        // hrvMs
            85,        // sleepScore
            now,
            now
        );

        assertThat(metric.userId()).isEqualTo("user-1");
        assertThat(metric.date()).isEqualTo(LocalDate.of(2026, 5, 27));
        assertThat(metric.steps()).isEqualTo(8500);
        assertThat(metric.restingHeartRate()).isEqualTo(58);
        assertThat(metric.sleepMinutes()).isEqualTo(420);
        assertThat(metric.hrvMs()).isEqualTo(72);
        assertThat(metric.sleepScore()).isEqualTo(85);
        assertThat(metric.createdAt()).isEqualTo(now);
        assertThat(metric.updatedAt()).isEqualTo(now);
    }

    @Test
    void record_allowsNull_forHrvAndSleepScore() {
        Instant now = Instant.now();
        DailyMetric metric = new DailyMetric(
            "user-2",
            LocalDate.of(2026, 5, 27),
            5000,
            62,
            390,
            null,      // hrvMs — nullable
            null,      // sleepScore — nullable
            now,
            now
        );

        assertThat(metric.hrvMs()).isNull();
        assertThat(metric.sleepScore()).isNull();
    }
}
