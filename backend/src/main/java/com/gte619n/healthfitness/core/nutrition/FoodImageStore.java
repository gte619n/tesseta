package com.gte619n.healthfitness.core.nutrition;

/**
 * Port for persisting a generated studio image to durable storage (IMPL-13
 * Milestone 4). Defined in {@code core} so {@link FoodImageService} can upload
 * without {@code core} depending on Google Cloud Storage. The concrete
 * implementation ({@code FoodImageStorage}, GCS) lives in {@code integrations}
 * and is injected via {@code ObjectProvider}, mirroring the
 * {@link MealPhotoStore} seam.
 *
 * <p>Implementations wrap storage errors rather than leaking raw GCS
 * exceptions.
 */
public interface FoodImageStore {

    /**
     * Upload the generated studio image (PNG bytes) for a food and return its
     * public URL.
     *
     * @param foodId     the catalog food id (used in the object path)
     * @param imageBytes the PNG image bytes
     * @return the public URL of the uploaded image
     */
    String upload(String foodId, byte[] imageBytes);

    /**
     * Look up a previously generated image for a content cache key (IMPL-13 M4
     * image reuse). Returns the public URL of an image generated for an identical
     * subject, or empty on a miss.
     *
     * <p>Default: always a miss, so stubs and core-only contexts simply
     * regenerate (no behavioural change without a caching store).
     */
    default java.util.Optional<String> findCachedUrl(String cacheKey) {
        return java.util.Optional.empty();
    }

    /**
     * Record that {@code url} is the generated image for {@code cacheKey}, so a
     * later identical subject reuses it instead of regenerating. Best-effort — a
     * failed write just means the next identical subject regenerates. Default
     * no-op.
     */
    default void putCachedUrl(String cacheKey, String url) {
    }
}
