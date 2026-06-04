package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.blood.BloodReading;
import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fake mirroring the Firestore repo's soft-delete (tombstone)
 * semantics from IMPL-AND-20 Phase 0: {@link #delete} archives the row rather
 * than removing it, and archived rows are excluded from {@link #findByUser} /
 * {@link #findById}. BloodReading has no status field of its own, so archived
 * ids are tracked in a side set (the Firestore impl uses the syncStatus key).
 */
public class InMemoryBloodReadingRepository implements BloodReadingRepository {

    private final Map<String, Map<String, BloodReading>> byUser = new ConcurrentHashMap<>();
    // userId -> set of archived (soft-deleted) reading ids.
    private final Map<String, Set<String>> archived = new ConcurrentHashMap<>();

    @Override
    public Optional<BloodReading> findById(String userId, String readingId) {
        if (isArchived(userId, readingId)) return Optional.empty();
        return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(readingId));
    }

    @Override
    public List<BloodReading> findByUser(String userId) {
        Set<String> dead = archived.getOrDefault(userId, Set.of());
        return byUser.getOrDefault(userId, Map.of()).entrySet().stream()
            .filter(e -> !dead.contains(e.getKey()))
            .map(Map.Entry::getValue)
            .sorted(Comparator.comparing(BloodReading::sampleDate).reversed())
            .limit(500)
            .toList();
    }

    @Override
    public void save(BloodReading r) {
        byUser.computeIfAbsent(r.userId(), k -> new ConcurrentHashMap<>())
            .put(r.readingId(), r);
        // Re-saving resurrects a previously-archived row (mirrors the Firestore
        // write re-stamping syncStatus=ACTIVE).
        Set<String> dead = archived.get(r.userId());
        if (dead != null) dead.remove(r.readingId());
    }

    @Override
    public void delete(String userId, String readingId) {
        // Soft-delete: tombstone the row, keep the underlying record.
        archived.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
            .add(readingId);
    }

    private boolean isArchived(String userId, String readingId) {
        return archived.getOrDefault(userId, Set.of()).contains(readingId);
    }
}
