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
    // catalog lifecycle
    ExerciseStatus status,
    String contributorId,                   // nullable
    Instant createdAt,
    Instant updatedAt,
    String aliasOfExerciseId                // merge pointer, nullable
) {}
