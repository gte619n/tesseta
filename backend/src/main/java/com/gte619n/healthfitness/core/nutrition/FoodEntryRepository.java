package com.gte619n.healthfitness.core.nutrition;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FoodEntryRepository {

    List<FoodEntry> findByDate(String userId, LocalDate date);

    Optional<FoodEntry> findById(String userId, LocalDate date, String entryId);

    void save(FoodEntry entry);

    void delete(String userId, LocalDate date, String entryId);
}
