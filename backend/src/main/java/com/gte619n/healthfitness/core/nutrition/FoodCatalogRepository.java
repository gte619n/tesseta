package com.gte619n.healthfitness.core.nutrition;

import java.util.List;
import java.util.Optional;

public interface FoodCatalogRepository {

    Optional<CatalogFood> findById(String foodId);

    List<CatalogFood> searchByNamePrefix(String prefixLower, int limit);

    /**
     * Foods whose {@code searchTokens} index contains <em>every</em> word in
     * {@code queryWords} (AND) — i.e. each typed word is a prefix of some word
     * in the food's name or brand. Backs any-word, order-insensitive search.
     * Returns up to {@code limit} matches; an empty query yields no results.
     */
    List<CatalogFood> searchByTokens(List<String> queryWords, int limit);

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

    /**
     * One-off backfill: recompute the {@code searchTokens} index for every
     * catalog food (used after the token-search rollout). Returns the count
     * reindexed. Default no-op for non-persistent stubs.
     */
    default int reindexSearchTokens() {
        return 0;
    }
}
