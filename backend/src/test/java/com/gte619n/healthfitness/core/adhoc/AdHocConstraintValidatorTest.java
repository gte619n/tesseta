package com.gte619n.healthfitness.core.adhoc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.EquipmentRequirement;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Unit-tests the pure constraint check (IMPL-ADHOC-01 D11). */
class AdHocConstraintValidatorTest {

    private static Exercise exercise(String id, List<EquipmentRequirement> reqs) {
        return new Exercise(id, id, id, List.of(), MovementPattern.OTHER, List.of(), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null, List.of(), reqs, List.of(BlockType.MAIN),
            null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }

    private static WorkoutDay dayWith(String... exerciseIds) {
        List<Prescription> rxs = new java.util.ArrayList<>();
        for (int i = 0; i < exerciseIds.length; i++) {
            rxs.add(new Prescription(exerciseIds[i], i, 3, 8, 12, null, null, 60, null, null, null, null));
        }
        return new WorkoutDay("d", "Day", null, null, 0,
            List.of(new Block("b", BlockType.MAIN, "Main", 0, rxs)));
    }

    @Test
    void bodyweightPassesEverywhere() {
        WorkoutDay day = dayWith("ex_pushup");
        Map<String, Exercise> byId = Map.of("ex_pushup", exercise("ex_pushup", List.of()));
        assertThat(AdHocConstraintValidator.check(day, Set.of(), byId).ok()).isTrue();
    }

    @Test
    void missingEquipmentIsFlagged() {
        WorkoutDay day = dayWith("ex_squat");
        // Requires a barbell that the (empty) gear set doesn't have.
        Exercise squat = exercise("ex_squat", List.of(new EquipmentRequirement(List.of("eq_barbell"))));
        var report = AdHocConstraintValidator.check(day, Set.of("eq_kettlebell"), Map.of("ex_squat", squat));
        assertThat(report.ok()).isFalse();
        assertThat(report.violations()).singleElement()
            .satisfies(v -> {
                assertThat(v.exerciseId()).isEqualTo("ex_squat");
                assertThat(v.reason()).contains("equipment");
            });
    }

    @Test
    void satisfiedEquipmentPasses() {
        WorkoutDay day = dayWith("ex_swing");
        Exercise swing = exercise("ex_swing", List.of(new EquipmentRequirement(List.of("eq_kettlebell"))));
        var report = AdHocConstraintValidator.check(day, Set.of("eq_kettlebell"), Map.of("ex_swing", swing));
        assertThat(report.ok()).isTrue();
    }

    @Test
    void unknownExerciseIsFlagged() {
        WorkoutDay day = dayWith("ex_ghost");
        var report = AdHocConstraintValidator.check(day, Set.of("eq_kettlebell"), Map.of());
        assertThat(report.ok()).isFalse();
        assertThat(report.violations()).singleElement()
            .satisfies(v -> assertThat(v.reason()).contains("not found"));
    }
}
