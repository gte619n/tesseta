package com.gte619n.healthfitness.core.nutrition;

import java.util.List;

/**
 * Persists the user's preferred display order for their drinks (IMPL-DRINK-01),
 * as an ordered list of drink {@code foodId}s. Kept off {@link CatalogFood} — the
 * order is a per-user preference, so it lives on the user document rather than
 * adding a positional field to the shared catalog record.
 */
public interface DrinkOrderRepository {

    /** The saved order of drink ids, or empty when the user has never reordered. */
    List<String> getOrder(String userId);

    /** Replace the saved order with {@code orderedDrinkIds}. */
    void saveOrder(String userId, List<String> orderedDrinkIds);
}
