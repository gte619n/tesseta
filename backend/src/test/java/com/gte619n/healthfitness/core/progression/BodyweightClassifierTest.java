package com.gte619n.healthfitness.core.progression;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** IMPL-PROG-02 F6: true bodyweight vs merely-unloaded. */
class BodyweightClassifierTest {

    @Test
    void namedBodyweightMovementsAreBodyweight() {
        assertThat(BodyweightClassifier.isBodyweight(
            ex("Dip", MovementPattern.PUSH_VERTICAL, Mechanic.COMPOUND, List.of()))).isTrue();
        assertThat(BodyweightClassifier.isBodyweight(
            ex("Pull-up", MovementPattern.PULL_VERTICAL, Mechanic.COMPOUND, List.of()))).isTrue();
        assertThat(BodyweightClassifier.isBodyweight(
            ex("Push-up", MovementPattern.PUSH_HORIZONTAL, Mechanic.COMPOUND, List.of()))).isTrue();
        assertThat(BodyweightClassifier.isBodyweight(
            ex("Muscle-up", MovementPattern.PULL_VERTICAL, Mechanic.COMPOUND, List.of()))).isTrue();
        assertThat(BodyweightClassifier.isBodyweight(
            ex("Inverted Row", MovementPattern.PULL_HORIZONTAL, Mechanic.COMPOUND, List.of()))).isTrue();
    }

    @Test
    void cablePushdownIsNotBodyweight() {
        // The exact regression: a weighted cable lift must NOT read as bodyweight
        // just because it has no prediction yet.
        Exercise pushdown = ex("Cable Push-down", MovementPattern.PUSH_HORIZONTAL,
            Mechanic.ISOLATION, List.of(new EquipmentRequirement(List.of("cable"))));
        assertThat(BodyweightClassifier.isBodyweight(pushdown)).isFalse();
    }

    @Test
    void equipmentLoadedMovementsAreNotBodyweight() {
        assertThat(BodyweightClassifier.isBodyweight(
            ex("Barbell Bench Press", MovementPattern.PUSH_HORIZONTAL, Mechanic.COMPOUND,
                List.of(new EquipmentRequirement(List.of("barbell")))))).isFalse();
    }

    @Test
    void nonLoadedPatternWithoutNameOrEquipmentIsNotBodyweight() {
        // An air squat has no equipment but SQUAT is not a bodyweight-loaded pattern.
        assertThat(BodyweightClassifier.isBodyweight(
            ex("Air Squat", MovementPattern.SQUAT, Mechanic.COMPOUND, List.of()))).isFalse();
    }

    @Test
    void nullExerciseIsNotBodyweight() {
        assertThat(BodyweightClassifier.isBodyweight(null)).isFalse();
    }

    private static Exercise ex(
        String name, MovementPattern pattern, Mechanic mechanic, List<EquipmentRequirement> equipment) {
        return new Exercise(
            name.toLowerCase().replace(' ', '-'), name, name.toLowerCase(), List.of(),
            pattern, List.of(), List.of(), Laterality.BILATERAL, mechanic, null, List.of(),
            equipment, List.of(BlockType.MAIN), null, false, List.of(), null, null,
            ExerciseMediaStatus.APPROVED, null, ExerciseMediaStatus.NONE, null,
            ExerciseStatus.PUBLISHED, null, Instant.now(), Instant.now(), null, false, List.of());
    }
}
