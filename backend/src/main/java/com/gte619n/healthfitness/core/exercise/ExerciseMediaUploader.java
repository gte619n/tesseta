package com.gte619n.healthfitness.core.exercise;

/**
 * Stores an admin-supplied demo frame for an exercise and persists the result.
 * Counterpart to {@link ExerciseMediaGenerator}; synchronous (bytes in hand).
 * Implementations live in {@code integrations}.
 */
public interface ExerciseMediaUploader {
    /**
     * Store the given image bytes as a candidate for the phase and make it the
     * active frame. Returns the updated exercise.
     */
    Exercise uploadFrame(String exerciseId, DemoPhase phase, byte[] bytes, String contentType);

    /**
     * Store the given image bytes as a candidate for an arbitrary plan
     * {@code key} (IMPL-19) and make it the active frame. Returns the updated
     * exercise.
     */
    Exercise uploadFrame(String exerciseId, String key, byte[] bytes, String contentType);

    /**
     * Remove a candidate frame and best-effort delete its backing GCS object.
     * If it was the active frame, falls back to the first remaining candidate
     * (or null). Returns the updated exercise.
     */
    Exercise deleteFrame(String exerciseId, DemoPhase phase, String imageUrl);

    /**
     * Key-based delete (IMPL-19): remove a candidate from the frame identified
     * by {@code key} and best-effort delete its backing GCS object.
     */
    Exercise deleteFrame(String exerciseId, String key, String imageUrl);

    /**
     * Store an admin-uploaded grounding reference photo and append its URL to
     * the exercise's {@code groundingImageUrls}. Unlike a frame candidate, the
     * image is stored at a grounding-distinct object path so it can later be
     * told apart from frame candidates. Returns the updated exercise.
     */
    Exercise uploadGroundingImage(String exerciseId, byte[] bytes, String contentType);

    /**
     * Remove {@code imageUrl} from the exercise's {@code groundingImageUrls}.
     * If (and only if) the URL is one of our own grounding uploads, its backing
     * GCS object is permanently deleted; candidate/external URLs are merely
     * unlinked. Returns the updated exercise.
     */
    Exercise removeGroundingImage(String exerciseId, String imageUrl);
}
