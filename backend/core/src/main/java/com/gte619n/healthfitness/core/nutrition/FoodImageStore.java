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
}
