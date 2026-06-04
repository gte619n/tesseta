package com.gte619n.healthfitness.core.exercise;

import java.util.List;

/**
 * Editable fields of an exercise, used by both create and update. On update a
 * null field means "leave unchanged"; on create nulls fall back to sensible
 * empties.
 */
public record ExerciseEdit(
    String name,
    List<String> aliases,
    MovementPattern movementPattern,
    List<String> primaryMuscles,
    List<String> secondaryMuscles,
    Laterality laterality,
    Mechanic mechanic,
    String description,
    List<String> formCues,
    List<EquipmentRequirement> requiredEquipment,
    List<BlockType> suitableBlockTypes,
    RepRange defaultRepRange,
    Boolean isTimed,
    String demoPromptOverride
) {}
