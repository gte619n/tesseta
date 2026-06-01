package com.gte619n.healthfitness.core.nutrition;

import java.util.List;
import java.util.Optional;

public interface FoodCatalogRepository {

    Optional<CatalogFood> findById(String foodId);

    List<CatalogFood> searchByNamePrefix(String prefixLower, int limit);

    /** Barcode equality lookup. Used by barcode resolution in M2. */
    Optional<CatalogFood> findByBarcode(String code);

    void save(CatalogFood food);

    /**
     * Foods whose studio image has not yet been generated
     * ({@code imageStatus == NONE}), limited for paged backfill (IMPL-13 M4).
     */
    List<CatalogFood> findByImageStatus(FoodImageStatus status, int limit);

    /** Idempotently records one distinct user's confirmation of a food. */
    void saveConfirmation(String foodId, String userId);

    int countConfirmations(String foodId);
}
