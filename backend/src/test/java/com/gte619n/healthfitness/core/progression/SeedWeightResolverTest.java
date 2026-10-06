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
    void dumbbellHingeSeedsPerHandNotBarbellScale() {
        // The barbell figure baseForPattern returns for a HINGE is a two-hand load.
        double barbell = SeedWeightResolver.seedWeightLbs(
            ex("Barbell Deadlift", MovementPattern.HINGE, Mechanic.COMPOUND));
        double dumbbell = SeedWeightResolver.seedWeightLbs(
            ex("Dumbbell Deadlift", MovementPattern.HINGE, Mechanic.COMPOUND));
        // The dumbbell variant is logged per hand, so its seed must be lighter —
        // roughly half — rather than the absurd 95 lb "deadlift" the old table gave.
        // Half of 95 is 47.5, which is no dumbbell anyone owns: the seed snaps DOWN
        // to the 5 lb rack step (45), never up.
        assertThat(dumbbell).isLessThan(barbell);
        assertThat(dumbbell).isEqualTo(45.0);
        assertThat(dumbbell).isGreaterThanOrEqualTo(SeedWeightResolver.FLOOR_LBS);
    }

    @Test
    void everySeedLandsOnARealFivePoundStep() {
        for (MovementPattern pattern : MovementPattern.values()) {
            for (String name : List.of("Dumbbell Deadlift", "Barbell Row", "Dumbbell Curl",
                "Cable Push-down", "Lateral Raise", "Weighted Crunch")) {
                double seed = SeedWeightResolver.seedWeightLbs(ex(name, pattern, Mechanic.COMPOUND));
                assertThat(seed % SeedWeightResolver.SEED_INCREMENT_LBS)
                    .as("%s / %s seeds %s", name, pattern, seed)
                    .isEqualTo(0.0);
            }
        }
    }

    @Test
    void rearDeltRaiseSeedsLightNotFortyPounds() {
        // Regression: a rear-delt raise used to hit the generic 40 lb accessory
        // ceiling (the "40 lb rear delt raise" bug). It must now seed with a small,
        // realistic dumbbell load — well under the cable-pushdown tier.
        double raise = SeedWeightResolver.seedWeightLbs(
            ex("Rear Delt Raise", MovementPattern.PUSH_VERTICAL, Mechanic.ISOLATION));
        assertThat(raise).isLessThanOrEqualTo(15.0);
        assertThat(raise).isGreaterThan(0.0);

        double pushdown = SeedWeightResolver.seedWeightLbs(
            ex("Cable Push-down", MovementPattern.PUSH_HORIZONTAL, Mechanic.ISOLATION));
        assertThat(raise).isLessThan(pushdown);
    }

    @Test
    void lateralRaiseFamilySeedsLight() {
        for (String name : List.of("Lateral Raise", "Dumbbell Front Raise", "Reverse Fly")) {
            double seed = SeedWeightResolver.seedWeightLbs(
                ex(name, MovementPattern.PUSH_VERTICAL, Mechanic.ISOLATION));
            assertThat(seed).as(name).isLessThanOrEqualTo(15.0).isGreaterThan(0.0);
        }
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
