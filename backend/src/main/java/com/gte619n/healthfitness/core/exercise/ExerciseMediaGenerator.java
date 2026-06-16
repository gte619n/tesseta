package com.gte619n.healthfitness.core.exercise;

import java.util.List;
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

    /** Regenerate a single phase only (legacy START/MID/END path). */
    CompletableFuture<Void> generatePhaseAsync(Exercise exercise, DemoPhase phase, String promptOverride);

    /**
     * Regenerate a single plan frame by its {@code key} (IMPL-19). For an
     * exercise with a {@code demoPlan} this targets the matching {@link FrameSpec};
     * for a plan-less exercise a legacy {@code start}/{@code mid}/{@code end} key
     * routes to the corresponding phase.
     */
    CompletableFuture<Void> generateFrameAsync(Exercise exercise, String key, String promptOverride);

    /**
     * Regenerate all plan frames (or all legacy phases) using an explicit
     * grounding override (IMPL-20). {@code referenceImageUrls} is the effective
     * grounding set for this run: {@code null} ⇒ use the exercise's persisted
     * {@code groundingImageUrls}; an empty list ⇒ explicitly no grounding; a
     * non-null list ⇒ use exactly those URLs. Resolved bytes are never
     * persisted — only the URL selection is stored (by the controller).
     */
    CompletableFuture<Void> generateDemoAsync(Exercise exercise, String promptOverride, List<String> referenceImageUrls);

    /**
     * Regenerate a single plan frame by {@code key} with an explicit grounding
     * override (IMPL-20). See {@link #generateDemoAsync(Exercise, String, List)}
     * for the {@code referenceImageUrls} semantics.
     */
    CompletableFuture<Void> generateFrameAsync(Exercise exercise, String key, String promptOverride, List<String> referenceImageUrls);

    /** The default prompt this generator would produce for a given phase. */
    String defaultPrompt(Exercise exercise, DemoPhase phase);

    /**
     * The exact composed image prompt this generator would use to regenerate the
     * frame identified by {@code key} (IMPL-19). When the exercise has a
     * {@code demoPlan} the {@link FrameSpec} matching {@code key} drives the
     * position clause; otherwise a legacy {@code start}/{@code mid}/{@code end}
     * key maps to the corresponding {@link DemoPhase}. Admin preview only — does
     * not call the model.
     */
    String promptFor(Exercise exercise, String key);
}
