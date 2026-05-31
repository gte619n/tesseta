package com.gte619n.healthfitness.core.nutrition;

import java.util.Optional;

/**
 * Port for reading back a previously stored meal/label photo by its reference
 * (the public URL returned from {@link MealPhotoStore#store}). Used by
 * {@link FoodImageService} to feed the user's real meal photo to the studio
 * image generator as a visual reference.
 *
 * <p>Defined in {@code core} so the orchestration stays GCS-free; the concrete
 * implementation ({@code FoodImageStorage}, GCS) lives in {@code integrations}
 * and is injected via {@code ObjectProvider}. Returns empty (never throws) when
 * the reference is missing/unreadable so generation can fall back to a
 * name-only prompt.
 */
public interface MealPhotoReader {

    /** Bytes plus content type of a stored photo. */
    record Photo(byte[] bytes, String mimeType) {}

    /**
     * Read the bytes of a stored photo by its reference.
     *
     * @param ref the storage reference (public URL) from {@link MealPhotoStore}
     * @return the photo bytes + mime, or empty if absent/unreadable
     */
    Optional<Photo> read(String ref);
}
