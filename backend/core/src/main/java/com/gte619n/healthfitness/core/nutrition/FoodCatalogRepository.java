package com.gte619n.healthfitness.core.nutrition;

import java.util.List;
import java.util.Optional;

public interface FoodCatalogRepository {

    Optional<CatalogFood> findById(String foodId);

    List<CatalogFood> searchByNamePrefix(String prefixLower, int limit);

    /** Barcode equality lookup. Used by barcode resolution in M2. */
    Optional<CatalogFood> findByBarcode(String code);

    void save(CatalogFood food);

    /** Idempotently records one distinct user's confirmation of a food. */
    void saveConfirmation(String foodId, String userId);

    int countConfirmations(String foodId);
}
