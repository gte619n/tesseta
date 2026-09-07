package com.gte619n.healthfitness.core.progression;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** IMPL-PROG-02 F6/D7: conservative first-time seed weight. */
class SeedWeightResolverTest {

    @Test
    void cablePushdownGetsALightRealNumber() {
        double seed = SeedWeightResolver.seedWeightLbs(
            ex("Cable Push-down", MovementPattern.PUSH_HORIZONTAL, Mechanic.ISOLATION));
        assertThat(seed).isGreaterThanOrEqualTo(SeedWeightResolver.FLOOR_LBS);
        assertThat(seed).isLessThanOrEqualTo(40.0);
        assertThat(seed).isGreaterThan(0.0); // never "body weight"
    }

    @Test
    void compoundLowerBodyStartsHeavierThanIsolation() {
        double squat = SeedWeightResolver.seedWeightLbs(
            ex("Barbell Back Squat", MovementPattern.SQUAT, Mechanic.COMPOUND));
        double curl = SeedWeightResolver.seedWeightLbs(
            ex("Dumbbell Curl", MovementPattern.PULL_VERTICAL, Mechanic.ISOLATION));
        assertThat(squat).isGreaterThan(curl);
    }

    @Test
    void neverBelowFloor() {
        double core = SeedWeightResolver.seedWeightLbs(
            ex("Weighted Crunch", MovementPattern.CORE, Mechanic.ISOLATION));
        assertThat(core).isGreaterThanOrEqualTo(SeedWeightResolver.FLOOR_LBS);
    }

    @Test
    void nullExerciseFallsBackToFloor() {
        assertThat(SeedWeightResolver.seedWeightLbs(null)).isEqualTo(SeedWeightResolver.FLOOR_LBS);
    }

    private static Exercise ex(String name, MovementPattern pattern, Mechanic mechanic) {
        return new Exercise(
            name.toLowerCase().replace(' ', '-'), name, name.toLowerCase(), List.of(),
            pattern, List.of(), List.of(), Laterality.BILATERAL, mechanic, null, List.of(),
            List.of(), List.of(BlockType.MAIN), null, false, List.of(), null, null,
            ExerciseMediaStatus.APPROVED, null, ExerciseMediaStatus.NONE, null,
            ExerciseStatus.PUBLISHED, null, Instant.now(), Instant.now(), null, false, List.of());
    }
}
