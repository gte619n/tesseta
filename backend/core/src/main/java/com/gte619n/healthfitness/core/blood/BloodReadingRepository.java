package com.gte619n.healthfitness.core.blood;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public interface BloodReadingRepository {
    Optional<BloodReading> findById(String userId, String readingId);
    List<BloodReading> findByUser(String userId);

    /**
     * Latest standalone reading for a single marker, or empty when the
     * user has none. The Firestore impl serves this with an indexed
     * {@code whereEqualTo(marker) + orderBy(sampleDate DESC).limit(1)}
     * query (composite index: marker ASC, sampleDate DESC). The default
     * reduces over {@link #findByUser} so in-memory test fakes keep
     * working without overriding it.
     */
    default Optional<BloodReading> findLatestByMarker(String userId, BloodMarker marker) {
        return findByUser(userId).stream()
            .filter(r -> r.marker() == marker)
            .filter(r -> r.sampleDate() != null)
            .max(Comparator.comparing(BloodReading::sampleDate));
    }

    void save(BloodReading reading);
    void delete(String userId, String readingId);
}
