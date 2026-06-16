package com.gte619n.healthfitness.core.exercise;

import java.time.Instant;
import java.util.List;

/**
 * A globally-shared, admin-curated movement in the exercise catalog (IMPL-14).
 * Mirrors the Equipment/Drug/CatalogFood pattern: a top-level Firestore
 * document with a contributor/approval lifecycle and an AI media pipeline.
 *
 * <p>{@code requiredEquipment} references the global Equipment catalog by id;
 * a gym executes this exercise iff every requirement group is satisfied by the
 * gym's {@code equipmentIds} (see {@link ExerciseAvailabilityService}).
 */
public record Exercise(
    String exerciseId,
    String name,
    String nameLower,                       // search index (lower-cased name)
    List<String> aliases,
    MovementPattern movementPattern,
    List<String> primaryMuscles,
    List<String> secondaryMuscles,
    Laterality laterality,
    Mechanic mechanic,
    String description,
    List<String> formCues,
    // equipment binding — every group must be satisfied; empty = bodyweight
    List<EquipmentRequirement> requiredEquipment,
    List<BlockType> suitableBlockTypes,
    RepRange defaultRepRange,               // nullable
    boolean isTimed,                        // cardio/holds use duration, not reps
    // demo media
    List<DemoFrame> demoFrames,
    String videoUrl,                        // RESERVED for future Veo; null in v1
    String demoPromptOverride,              // nullable; null = use built default
    ExerciseMediaStatus mediaStatus,
    // IMPL-19: the model-derived, admin-reviewed frame plan + its review status,
    // and the public-library reference used for grounding (all additive)
    List<FrameSpec> demoPlan,               // nullable; null = legacy START/MID/END pattern
    ExerciseMediaStatus planStatus,         // defaults to NONE
    ExerciseReference reference,            // nullable
    // catalog lifecycle
    ExerciseStatus status,
    String contributorId,                   // nullable
    Instant createdAt,
    Instant updatedAt,
    String aliasOfExerciseId,               // merge pointer, nullable
    // IMPL-20 (additive): human whole-exercise sign-off + the persisted set of
    // image URLs (own GCS candidates and/or external reference URLs) fed back
    // into regeneration as pose references. Legacy docs default to false/[].
    boolean reviewed,
    List<String> groundingImageUrls
) {}
