package com.gte619n.healthfitness.integrations.exercise;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseMediaStatus;
import com.gte619n.healthfitness.core.exercise.ExerciseStatus;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Deterministic checks on the Veo prompt builder — no API calls. */
class ExerciseVideoPromptTest {

    private static Exercise gobletSquat() {
        Instant now = Instant.now();
        return new Exercise(
            "ex-test", "Dumbbell Goblet Squat", "dumbbell goblet squat", List.of(),
            MovementPattern.SQUAT, List.of("quadriceps", "glutes"), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null,
            List.of("Hold the dumbbell at your chest", "Push your knees out"),
            List.of(), List.of(), null, false,
            List.of(), null, null, ExerciseMediaStatus.NONE,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, now, now, null, false, List.of());
    }

    private static Exercise benchPress() {
        Instant now = Instant.now();
        return new Exercise(
            "ex-bench", "Barbell Bench Press", "barbell bench press", List.of(),
            MovementPattern.PUSH_HORIZONTAL, List.of("chest"), List.of(),
            Laterality.BILATERAL, Mechanic.COMPOUND, null,
            List.of("Lower the bar to mid-chest"),
            List.of(), List.of(), null, false,
            List.of(), null, null, ExerciseMediaStatus.NONE,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, now, now, null, false, List.of());
    }

    @Test
    void sideViewPromptCarriesActorEquipmentExerciseAndFullRom() {
        String p = ExerciseVideoPrompt.build(gobletSquat(), ExerciseVideoPrompt.View.SIDE);
        assertThat(p)
            .contains("Dumbbell Goblet Squat")
            .contains("Hold the dumbbell at your chest")
            .contains("same single athlete")            // consistent actor
            .contains("pair of appropriately sized dumbbells")  // exercise-aware equipment
            .contains("real resistance")                 // video tempo note still present
            .contains("9:16")
            .containsIgnoringCase("side-profile")
            .contains("static throughout")               // locked-off side view
            .contains("COMPLETE, full range of motion")  // full ROM enforced
            .contains("below the knees");                // squat-specific ROM
    }

    @Test
    void standingFrontViewOrbitsToASecondAngle() {
        String p = ExerciseVideoPrompt.build(gobletSquat(), ExerciseVideoPrompt.View.FRONT);
        assertThat(p)
            .contains("orbit")                            // standing → orbit to front
            .contains("front three-quarter")
            .contains("matching the reference image")     // seeded from the same still
            .contains("pair of appropriately sized dumbbells");  // exercise-aware equipment
    }

    @Test
    void lyingFrontViewStaysLyingInsteadOfOrbiting() {
        String p = ExerciseVideoPrompt.build(benchPress(), ExerciseVideoPrompt.View.FRONT);
        assertThat(p)
            .contains("stays lying flat")                 // no sitting up
            .contains("never sits up")
            .contains("chest to full lockout")            // full ROM on the press
            .doesNotContain("orbit around the standing");
    }
}
