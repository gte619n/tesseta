package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.blood.BloodReading;
import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryBloodReadingRepository implements BloodReadingRepository {

    private final Map<String, Map<String, BloodReading>> byUser = new ConcurrentHashMap<>();

    @Override
    public Optional<BloodReading> findById(String userId, String readingId) {
        return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(readingId));
    }

    @Override
    public List<BloodReading> findByUser(String userId) {
        return byUser.getOrDefault(userId, Map.of()).values().stream()
            .sorted(Comparator.comparing(BloodReading::sampleDate).reversed())
            .limit(500)
            .toList();
    }

    @Override
    public void save(BloodReading r) {
        byUser.computeIfAbsent(r.userId(), k -> new ConcurrentHashMap<>())
            .put(r.readingId(), r);
    }

    @Override
    public void delete(String userId, String readingId) {
        Map<String, BloodReading> u = byUser.get(userId);
        if (u != null) u.remove(readingId);
    }
}
