package com.gte619n.healthfitness.core.nutrition;

import java.util.Set;

/**
 * Per-user "hidden from search" list over the shared {@link SavedMeal} catalog.
 *
 * <p>The saved-meal catalog is global (one document per dish, shared across
 * users), so hiding a duplicate can't mutate or delete the shared document.
 * Instead each user keeps their own set of archived {@code mealId}s; the add-food
 * meal search filters those out. Archiving never touches already-logged entries —
 * an {@link FoodEntry} copies the meal's ingredients and macros at log time and
 * holds no reference back to the {@code mealId}, so anything linked to the meal
 * stays intact.
 */
public interface ArchivedMealRepository {

    /** Hide {@code mealId} from {@code userId}'s meal search. Idempotent. */
    void archive(String userId, String mealId);

    /** The set of meal ids {@code userId} has archived (may be empty). */
    Set<String> archivedMealIds(String userId);
}
