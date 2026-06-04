package com.gte619n.healthfitness.core.bodycomposition;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface BodyCompositionRepository {
    Optional<BodyCompositionMeasurement> findById(String userId, String recordId);
    List<BodyCompositionMeasurement> findByUserAndRange(
        String userId,
        BodyCompositionMetric metric,
        Instant from,
        Instant to
    );
    List<BodyCompositionMeasurement> findByUser(String userId);

    /**
     * Latest measurement for a single metric, or empty when the user has
     * none. The Firestore impl serves this with an indexed
     * {@code whereEqualTo(metric) + orderBy(sampleTime DESC).limit(1)}
     * query (composite index: metric ASC, sampleTime DESC). The default
     * here reduces over {@link #findByUser} so in-memory test fakes keep
     * working without overriding it.
     */
    default Optional<BodyCompositionMeasurement> findLatest(String userId, BodyCompositionMetric metric) {
        return findByUser(userId).stream()
            .filter(m -> m.metric() == metric)
            .filter(m -> m.sampleTime() != null)
            .max(java.util.Comparator.comparing(BodyCompositionMeasurement::sampleTime));
    }

    void save(BodyCompositionMeasurement measurement);
    void saveAll(List<BodyCompositionMeasurement> measurements);
    void delete(String userId, String recordId);
    void deleteByUserMetricAndRange(
        String userId,
        BodyCompositionMetric metric,
        Instant from,
        Instant to
    );
}
