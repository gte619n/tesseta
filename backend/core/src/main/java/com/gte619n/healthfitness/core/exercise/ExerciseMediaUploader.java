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
     * Remove a candidate frame and best-effort delete its backing GCS object.
     * If it was the active frame, falls back to the first remaining candidate
     * (or null). Returns the updated exercise.
     */
    Exercise deleteFrame(String exerciseId, DemoPhase phase, String imageUrl);
}
