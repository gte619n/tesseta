package com.gte619n.healthfitness.core.nutrition;

/**
 * Port for persisting a raw user-captured meal/label photo to durable storage.
 *
 * <p>Defined in {@code core} so {@link NutritionCaptureService} can store the
 * photo without {@code core} depending on Google Cloud Storage. The concrete
 * implementation ({@code MealPhotoStorage}, GCS) lives in {@code integrations}
 * and is injected via {@code ObjectProvider} so core unit tests run without it,
 * mirroring the {@link BarcodeLookup} seam.
 *
 * <p>Implementations wrap storage errors rather than leaking raw GCS
 * exceptions.
 */
public interface MealPhotoStore {

    /**
     * Upload a raw photo for a user and return a reference to it.
     *
     * @param userId     the owning user
     * @param imageBytes the raw photo bytes
     * @param mimeType   the image content type (e.g. {@code image/jpeg})
     * @return a storage reference (a public URL) for the stored object
     */
    String store(String userId, byte[] imageBytes, String mimeType);

    /**
     * Best-effort delete of a stored photo by its reference. Used to discard the
     * transient leftover photo once analysis is done (IMPL-LEFTOVER-01, spec D11).
     * Implementations must not throw — a failed delete just leaves an orphan object.
     * Default is a no-op so alternative stores need not implement it.
     *
     * @param ref the storage reference (public URL) returned from {@link #store}
     */
    default void delete(String ref) {
        // no-op by default
    }
}
