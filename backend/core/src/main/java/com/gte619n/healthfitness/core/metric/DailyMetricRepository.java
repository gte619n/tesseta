package com.gte619n.healthfitness.core.metric;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// TODO(IMPL-12 follow-up): when a writer for {vitals.restingHr, vitals.hrv, vitals.sleepScore} exists, publish MetricChangedEvent via MetricChangedPublisher.
public interface DailyMetricRepository {
    Optional<DailyMetric> findByDate(String userId, LocalDate date);
    List<DailyMetric> findByDateRange(String userId, LocalDate from, LocalDate to);

    /**
     * The {@code limit} most-recent daily metric documents, newest first.
     *
     * Callers scan these in order for the first row carrying the field
     * they need (vitals fields arrive on different days via merge
     * semantics, so the newest doc may not have every field). The
     * Firestore impl serves this with an indexed
     * {@code orderBy(date DESC).limit(limit)} read (single-field index on
     * {@code date}, auto-created) instead of paging a whole date range.
     * The default reduces over {@link #findByDateRange} so in-memory test
     * fakes keep working without overriding it.
     */
    default List<DailyMetric> findLatestDailyMetric(String userId, int limit) {
        LocalDate to = LocalDate.now();
        // Generous lookback so the reduce sees the same rows the indexed
        // impl would; callers only inspect the newest non-null per field.
        LocalDate from = to.minusYears(2);
        return findByDateRange(userId, from, to).stream()
            .filter(m -> m.date() != null)
            .sorted(Comparator.comparing(DailyMetric::date).reversed())
            .limit(limit)
            .toList();
    }

    void save(DailyMetric metric);
}
