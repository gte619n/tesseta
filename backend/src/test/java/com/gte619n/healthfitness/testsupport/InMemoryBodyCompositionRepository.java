package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Test fake. Same role as InMemoryUserRepository — stands in for the
// Firestore-backed BodyCompositionRepository in unit tests so the Spring
// context wires cleanly.
public class InMemoryBodyCompositionRepository implements BodyCompositionRepository {

    // (userId, recordId) -> measurement
    private final Map<String, Map<String, BodyCompositionMeasurement>> byUser = new ConcurrentHashMap<>();

    @Override
    public Optional<BodyCompositionMeasurement> findById(String userId, String recordId) {
        return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(recordId));
    }

    @Override
    public List<BodyCompositionMeasurement> findByUserAndRange(
        String userId, BodyCompositionMetric metric, Instant from, Instant to
    ) {
        return byUser.getOrDefault(userId, Map.of()).values().stream()
            .filter(m -> m.metric() == metric)
            .filter(m -> !m.sampleTime().isBefore(from) && !m.sampleTime().isAfter(to))
            .sorted(Comparator.comparing(BodyCompositionMeasurement::sampleTime).reversed())
            .toList();
    }

    @Override
    public List<BodyCompositionMeasurement> findByUser(String userId) {
        return byUser.getOrDefault(userId, Map.of()).values().stream()
            .sorted(Comparator.comparing(BodyCompositionMeasurement::sampleTime).reversed())
            .limit(500)
            .toList();
    }

    @Override
    public void save(BodyCompositionMeasurement m) {
        byUser.computeIfAbsent(m.userId(), k -> new ConcurrentHashMap<>())
            .put(m.recordId(), m);
    }

    @Override
    public void saveAll(List<BodyCompositionMeasurement> measurements) {
        measurements.forEach(this::save);
    }

    @Override
    public void delete(String userId, String recordId) {
        Map<String, BodyCompositionMeasurement> u = byUser.get(userId);
        if (u != null) u.remove(recordId);
    }

    @Override
    public void deleteByUserMetricAndRange(
        String userId, BodyCompositionMetric metric, Instant from, Instant to
    ) {
        Map<String, BodyCompositionMeasurement> u = byUser.get(userId);
        if (u == null) return;
        u.values().removeIf(m ->
            m.metric() == metric
                && !m.sampleTime().isBefore(from)
                && !m.sampleTime().isAfter(to));
    }
}
