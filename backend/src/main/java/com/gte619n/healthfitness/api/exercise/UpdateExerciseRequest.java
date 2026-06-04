package com.gte619n.healthfitness.api.exercise;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.ExerciseEdit;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.exercise.RepRange;
import java.util.List;

/** All fields nullable: null means "leave unchanged". */
public record UpdateExerciseRequest(
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
) {
    public ExerciseEdit toEdit() {
        return new ExerciseEdit(name, aliases, movementPattern, primaryMuscles, secondaryMuscles,
            laterality, mechanic, description, formCues, requiredEquipment, suitableBlockTypes,
            defaultRepRange, isTimed, demoPromptOverride);
    }
}
