package com.gte619n.healthfitness.core.exercise;

/**
 * Lifecycle of an exercise's demo media. Generated media never auto-publishes:
 * it lands {@code NEEDS_REVIEW} until an admin approves it (the
 * anatomical-correctness gate from docs/photography-prompts.md).
 */
public enum ExerciseMediaStatus {
    NONE, PENDING, NEEDS_REVIEW, APPROVED, FAILED
}
