package com.gte619n.healthfitness.api.biometrics;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

// Computes the per-metric biometrics summary (latest reading + 60-day cadence)
// shown in the settings section, reading the same provider-agnostic stores the
// dashboard uses. Cadence window is a fixed 60 days ("last 2 months").
@Service
public class BiometricsService {

    static final int WINDOW_DAYS = 60;

    private final BodyCompositionRepository body;
    private final DailyMetricRepository daily;

    public BiometricsService(BodyCompositionRepository body, DailyMetricRepository daily) {
        this.body = body;
        this.daily = daily;
    }

    public List<BiometricSummary> summaries(String userId, Collection<String> hidden) {
        Instant now = Instant.now();
        Instant fromInstant = now.minus(Duration.ofDays(WINDOW_DAYS));
        LocalDate fromDate = fromInstant.atZone(ZoneOffset.UTC).toLocalDate();
        LocalDate toDate = now.atZone(ZoneOffset.UTC).toLocalDate();

        // One range read of the daily series feeds all four daily metrics.
        List<DailyMetric> dailyRows = daily.findByDateRange(userId, fromDate, toDate);

        List<BiometricSummary> out = new ArrayList<>();
        for (Biometric b : Biometric.values()) {
            boolean visible = !hidden.contains(b.name());
            out.add(b.isBody()
                ? bodySummary(userId, b, visible, fromInstant, now)
                : dailySummary(b, visible, dailyRows));
        }
        return out;
    }

    private BiometricSummary bodySummary(
        String userId, Biometric b, boolean visible, Instant from, Instant to) {
        List<BodyCompositionMeasurement> rows =
            body.findByUserAndRange(userId, b.bodyMetric(), from, to);
        var latest = rows.stream().max(Comparator.comparing(BodyCompositionMeasurement::sampleTime));
        return new BiometricSummary(
            b.name(), b.label(), b.unit(), visible,
            latest.map(BodyCompositionMeasurement::value).orElse(null),
            latest.map(BodyCompositionMeasurement::sampleTime).orElse(null),
            rows.size(), avgIntervalDays(rows.size()));
    }

    private BiometricSummary dailySummary(Biometric b, boolean visible, List<DailyMetric> dailyRows) {
        List<Map.Entry<LocalDate, Integer>> points = new ArrayList<>();
        for (DailyMetric dm : dailyRows) {
            Integer v = dailyValue(b, dm);
            if (v != null) points.add(Map.entry(dm.date(), v));
        }
        var latest = points.stream().max(Comparator.comparing(Map.Entry::getKey));
        return new BiometricSummary(
            b.name(), b.label(), b.unit(), visible,
            latest.map(e -> (double) e.getValue()).orElse(null),
            latest.map(e -> e.getKey().atStartOfDay(ZoneOffset.UTC).toInstant()).orElse(null),
            points.size(), avgIntervalDays(points.size()));
    }

    // "One every N days" over the window. Null when there are no readings.
    private static Double avgIntervalDays(int count) {
        return count >= 1 ? WINDOW_DAYS / (double) count : null;
    }

    private static Integer dailyValue(Biometric b, DailyMetric dm) {
        return switch (b) {
            case SLEEP -> dm.sleepMinutes();
            case STEPS -> dm.steps();
            case RESTING_HR -> dm.restingHeartRate();
            case HRV -> dm.hrvMs();
            default -> null;
        };
    }
}
