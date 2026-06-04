package com.gte619n.healthfitness.core.equipment;

/**
 * Stores an admin-supplied image for a piece of equipment and persists the
 * result.
 *
 * <p>This is the counterpart to {@link EquipmentImageGenerator}: instead of
 * synthesizing an image with the AI pipeline, the admin uploads a specific
 * photo. Implementations live in higher modules (e.g. {@code integrations})
 * so {@code core} can depend only on this abstraction.
 *
 * <p>Unlike generation, this is synchronous: the bytes are already in hand,
 * so the call uploads to storage, appends the new url to the candidate
 * gallery, sets {@code imageStatus = GENERATED} and the new active
 * {@code imageUrl}, and returns the updated {@link Equipment}. Prior images
 * are kept as candidates (see {@link #deleteImage} to remove one).
 */
public interface EquipmentImageUploader {
    /**
     * Store the given image bytes for the equipment and return the updated
     * record (with the new {@code imageUrl} and {@code imageStatus =
     * GENERATED}).
     *
     * @param equipmentId the equipment to attach the image to
     * @param bytes       the raw image bytes
     * @param contentType the MIME type of the bytes (e.g. {@code image/png})
     * @return the updated equipment
     * @throws IllegalArgumentException if the equipment does not exist
     */
    Equipment uploadImage(String equipmentId, byte[] bytes, String contentType);

    /**
     * Remove an image candidate and best-effort delete its backing GCS
     * object. If the removed url was the active {@code imageUrl}, the active
     * image falls back to the first remaining candidate (or null when none
     * remain).
     *
     * <p>Lives here rather than in {@code core}'s {@link EquipmentService}
     * because deleting the stored blob requires the storage client, which
     * only exists in higher modules.
     *
     * @param equipmentId the equipment to remove the candidate from
     * @param imageUrl    the candidate url to delete
     * @return the updated equipment
     * @throws IllegalArgumentException if the equipment does not exist or the
     *   url is not one of its candidates
     */
    Equipment deleteImage(String equipmentId, String imageUrl);
}
