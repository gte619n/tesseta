package com.gte619n.healthfitness.core.exercise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.testsupport.InMemoryExerciseRepository;
import com.gte619n.healthfitness.testsupport.InMemoryLocationRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExerciseSuggestionServiceTest {

    private static final String USER = "u-sug";
    private static final String GYM = "gym";

    private InMemoryExerciseRepository exercises;
    private ExerciseSuggestionService service;

    @BeforeEach
    void setUp() {
        exercises = new InMemoryExerciseRepository();
        InMemoryLocationRepository locations = new InMemoryLocationRepository();
        // A bodyweight-only gym (no equipment): every no-requirement exercise is executable.
        locations.save(new Location(USER, GYM, GYM, null, null, true, Map.of(), List.of(),
            List.of(), Map.of(), false, true, Instant.now(), Instant.now()));
        ExerciseAvailabilityService availability =
            new ExerciseAvailabilityService(exercises, locations, true);
        service = new ExerciseSuggestionService(availability, exercises);

        // Reference: a back squat — quads/glutes, SQUAT pattern, compound.
        exercises.save(exercise("back-squat", "Back Squat",
            List.of("quads", "glutes"), List.of("hamstrings"), MovementPattern.SQUAT, Mechanic.COMPOUND));
        // Same primary muscles + same pattern → strongest match.
        exercises.save(exercise("goblet-squat", "Goblet Squat",
            List.of("quads", "glutes"), List.of("core"), MovementPattern.SQUAT, Mechanic.COMPOUND));
        // Shares one primary + same pattern → medium.
        exercises.save(exercise("lunge", "Walking Lunge",
            List.of("quads"), List.of("glutes"), MovementPattern.LUNGE, Mechanic.COMPOUND));
        // Unrelated upper-body isolation → weakest.
        exercises.save(exercise("bicep-curl", "Bicep Curl",
            List.of("biceps"), List.of(), MovementPattern.PULL_VERTICAL, Mechanic.ISOLATION));
    }

    @Test
    void ranksSameMuscleExercisesFirstAndExcludesReference() {
        List<String> ids = service.rankedFor(USER, GYM, "back-squat", null).stream()
            .map(Exercise::exerciseId).toList();

        // The reference itself is never suggested.
        assertFalse(ids.contains("back-squat"));
        // Same-muscle/same-pattern floats to the top; the unrelated curl sinks.
        assertEquals(List.of("goblet-squat", "lunge", "bicep-curl"), ids);
    }

    @Test
    void searchNarrowsByNameOrAlias() {
        List<String> ids = service.rankedFor(USER, GYM, "back-squat", "lunge").stream()
            .map(Exercise::exerciseId).toList();
        assertEquals(List.of("lunge"), ids);
    }

    @Test
    void withoutReferenceFallsBackToAlphabetical() {
        List<String> ids = service.rankedFor(USER, GYM, null, null).stream()
            .map(Exercise::exerciseId).toList();
        // No reference → score 0 everywhere → alphabetical by name, ref included.
        assertEquals(List.of("back-squat", "bicep-curl", "goblet-squat", "lunge"), ids);
    }

    private static Exercise exercise(
        String id, String name, List<String> primary, List<String> secondary,
        MovementPattern pattern, Mechanic mechanic
    ) {
        return new Exercise(id, name, name.toLowerCase(), List.of(), pattern, primary, secondary,
            Laterality.BILATERAL, mechanic, null, List.of(), List.of(), List.of(BlockType.MAIN),
            null, false, List.of(), null, null, ExerciseMediaStatus.APPROVED,
            null, ExerciseMediaStatus.NONE, null, ExerciseStatus.PUBLISHED,
            null, Instant.now(), Instant.now(), null, false, List.of());
    }
}
