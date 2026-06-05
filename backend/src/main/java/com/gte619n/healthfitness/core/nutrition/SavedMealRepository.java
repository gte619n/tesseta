package com.gte619n.healthfitness.core.nutrition;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for the shared {@link SavedMeal} catalog. Mirrors
 * {@link FoodCatalogRepository}: a name-prefix search backs the "find a previous
 * meal" step, and {@code findByImageStatus} backs an image backfill. The
 * Firestore implementation lives in {@code persistence}; core unit tests use an
 * in-memory fake.
 */
public interface SavedMealRepository {

    Optional<SavedMeal> findById(String mealId);

    /**
     * Saved meals whose {@code nameLower} begins with {@code prefixLower},
     * ordered by name, capped at {@code limit}. Global (all users) — the service
     * ranks the requesting user's own meals first.
     */
    List<SavedMeal> searchByNamePrefix(String prefixLower, int limit);

    void save(SavedMeal meal);

    List<SavedMeal> findByImageStatus(FoodImageStatus status, int limit);
}
