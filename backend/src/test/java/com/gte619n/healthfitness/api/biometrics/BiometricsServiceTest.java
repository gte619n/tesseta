package com.gte619n.healthfitness.api.biometrics;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.testsupport.InMemoryBodyCompositionRepository;
import com.gte619n.healthfitness.testsupport.InMemoryDailyMetricRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BiometricsServiceTest {

    private final InMemoryBodyCompositionRepository body = new InMemoryBodyCompositionRepository();
    private final InMemoryDailyMetricRepository daily = new InMemoryDailyMetricRepository();
    private final BiometricsService service = new BiometricsService(body, daily);

    private static BiometricSummary byKey(List<BiometricSummary> all, String key) {
        return all.stream().filter(s -> s.key().equals(key)).findFirst().orElseThrow();
    }

    private static BodyCompositionMeasurement weight(String recordId, double kg, Instant at) {
        return new BodyCompositionMeasurement(
            "u", recordId, BodyCompositionMetric.WEIGHT_KG, kg, at, "WITHINGS", "AUTOMATIC", null, null);
    }

    @Test
    void weightSummaryReportsLatestAndCadence() {
        Instant t1 = Instant.now().minus(Duration.ofDays(10));
        Instant t2 = Instant.now().minus(Duration.ofDays(2));
        body.save(weight("r1", 89.0, t1));
        body.save(weight("r2", 90.5, t2));

        BiometricSummary w = byKey(service.summaries("u", Set.of()), "WEIGHT");

        assertThat(w.latestValue()).isEqualTo(90.5);
        assertThat(w.latestAt()).isEqualTo(t2);
        assertThat(w.observationsLast60d()).isEqualTo(2);
        assertThat(w.avgIntervalDays()).isEqualTo(30.0); // 60 / 2
        assertThat(w.unit()).isEqualTo("kg");
        assertThat(w.visible()).isTrue();
    }

    @Test
    void dailySummariesComeFromTheSeriesAndRespectHidden() {
        LocalDate d1 = LocalDate.now(ZoneOffset.UTC).minusDays(5);
        LocalDate d2 = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        daily.save(new DailyMetric("u", d1, null, null, 450, null, null, null, null));
        daily.save(new DailyMetric("u", d2, 8000, null, 480, null, null, null, null));

        List<BiometricSummary> summaries = service.summaries("u", Set.of("SLEEP"));

        BiometricSummary sleep = byKey(summaries, "SLEEP");
        assertThat(sleep.latestValue()).isEqualTo(480.0);
        assertThat(sleep.observationsLast60d()).isEqualTo(2);
        assertThat(sleep.visible()).isFalse(); // hidden

        BiometricSummary steps = byKey(summaries, "STEPS");
        assertThat(steps.latestValue()).isEqualTo(8000.0);
        assertThat(steps.observationsLast60d()).isEqualTo(1);
        assertThat(steps.visible()).isTrue();
    }

    @Test
    void metricWithNoDataHasNullsAndZeroCount() {
        BiometricSummary hrv = byKey(service.summaries("u", Set.of()), "HRV");
        assertThat(hrv.latestValue()).isNull();
        assertThat(hrv.latestAt()).isNull();
        assertThat(hrv.observationsLast60d()).isZero();
        assertThat(hrv.avgIntervalDays()).isNull();
    }
}
