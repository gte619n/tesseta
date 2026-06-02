package com.gte619n.healthfitness.core.exercise;

import java.util.concurrent.CompletableFuture;

/**
 * Generates demo media (phase stills) for an exercise and persists the result.
 * Mirrors {@code EquipmentImageGenerator}: fire-and-forget, updates the
 * exercise's {@code demoFrames}/{@code mediaStatus} on completion. On success
 * the implementation sets {@code mediaStatus = NEEDS_REVIEW} (never APPROVED) —
 * exercise media requires a human anatomical review before publishing.
 *
 * <p>Implementations live in higher modules (e.g. {@code integrations}) so
 * {@code core} depends only on this abstraction.
 */
public interface ExerciseMediaGenerator {
    /** Regenerate all phases (START/MID/END) for the exercise. */
    CompletableFuture<Void> generateDemoAsync(Exercise exercise);

    /** Regenerate all phases using {@code promptOverride} verbatim (null = default). */
    CompletableFuture<Void> generateDemoAsync(Exercise exercise, String promptOverride);

    /** Regenerate a single phase only. */
    CompletableFuture<Void> generatePhaseAsync(Exercise exercise, DemoPhase phase, String promptOverride);

    /** The default prompt this generator would produce for a given phase. */
    String defaultPrompt(Exercise exercise, DemoPhase phase);
}
