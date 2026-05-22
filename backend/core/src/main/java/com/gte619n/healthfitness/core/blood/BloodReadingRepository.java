package com.gte619n.healthfitness.core.blood;

import java.util.List;
import java.util.Optional;

public interface BloodReadingRepository {
    Optional<BloodReading> findById(String userId, String readingId);
    List<BloodReading> findByUser(String userId);
    void save(BloodReading reading);
    void delete(String userId, String readingId);
}
