package com.gte619n.healthfitness.core.exercise;

import java.util.List;

/**
 * A seed definition for the exercise catalog. {@code requiredEquipmentNames} is
 * a list of any-of groups expressed by equipment NAME (resolved to catalog ids
 * at seed time); an empty list means bodyweight.
 */
public record ExerciseSeed(
    String name,
    MovementPattern movementPattern,
    Mechanic mechanic,
    Laterality laterality,
    List<String> primaryMuscles,
    List<String> secondaryMuscles,
    List<String> formCues,
    List<BlockType> suitableBlockTypes,
    boolean isTimed,
    Integer repMin,
    Integer repMax,
    List<List<String>> requiredEquipmentNames
) {}
