package com.gte619n.healthfitness.core.nutrition;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FoodEntryRepository {

    List<FoodEntry> findByDate(String userId, LocalDate date);

    Optional<FoodEntry> findById(String userId, LocalDate date, String entryId);

    // Find an entry on this day whose captured photo matches the given SHA-256
    // content hash, if any. Used to dedupe re-uploads of the same meal photo
    // before spending another Gemini call — see MealCaptureService.captureMeal.
    Optional<FoodEntry> findByContentHash(String userId, LocalDate date, String contentHash);

    void save(FoodEntry entry);

    void delete(String userId, LocalDate date, String entryId);
}
