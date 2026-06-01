package com.gte619n.healthfitness.testsupport.exercise;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.exercise.ExerciseMetadataEnricher;
import com.gte619n.healthfitness.core.exercise.Laterality;
import com.gte619n.healthfitness.core.exercise.Mechanic;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.exercise.RepRange;
import java.util.List;

/**
 * Deterministic, offline stand-in for the live Gemini enricher used in tests and
 * in the seed-preview integration test. Derives plausible metadata from simple
 * name keywords so the preview output is meaningful without any API calls.
 */
public class FakeExerciseMetadataEnricher implements ExerciseMetadataEnricher {

    @Override
    public Enrichment enrich(String name) {
        if (name == null || name.isBlank()) {
            return ExerciseMetadataEnricher.empty(name);
        }
        String n = name.toLowerCase();
        boolean timed = containsAny(n, "recover", "plank", "carry", "hold", "run", "skip", "a-skip", "march");

        MovementPattern pattern;
        List<String> primary;
        if (containsAny(n, "squat")) { pattern = MovementPattern.SQUAT; primary = List.of("quadriceps", "glutes"); }
        else if (containsAny(n, "lunge", "split squat", "step")) { pattern = MovementPattern.LUNGE; primary = List.of("quadriceps", "glutes"); }
        else if (containsAny(n, "deadlift", "hinge", "rdl", "good morning", "swing")) { pattern = MovementPattern.HINGE; primary = List.of("hamstrings", "glutes"); }
        else if (containsAny(n, "row", "pull-down", "pulldown", "pull down", "lat")) { pattern = MovementPattern.PULL_HORIZONTAL; primary = List.of("back", "biceps"); }
        else if (containsAny(n, "pull-up", "pull up", "chin")) { pattern = MovementPattern.PULL_VERTICAL; primary = List.of("back", "biceps"); }
        else if (containsAny(n, "overhead press", "shoulder press", "military")) { pattern = MovementPattern.PUSH_VERTICAL; primary = List.of("shoulders", "triceps"); }
        else if (containsAny(n, "bench", "press", "push-up", "push up", "fly", "chest")) { pattern = MovementPattern.PUSH_HORIZONTAL; primary = List.of("chest", "triceps"); }
        else if (containsAny(n, "curl")) { pattern = MovementPattern.OTHER; primary = List.of("biceps"); }
        else if (containsAny(n, "plank", "crunch", "bird dog", "dead bug", "core", "ab ")) { pattern = MovementPattern.CORE; primary = List.of("core"); }
        else if (containsAny(n, "carry", "run", "skip", "march")) { pattern = MovementPattern.CARDIO; primary = List.of("full body"); }
        else { pattern = MovementPattern.OTHER; primary = List.of("full body"); }

        Laterality laterality = containsAny(n, "single", "alternating", "one-", "3-point", "b-stance")
            ? Laterality.UNILATERAL : Laterality.BILATERAL;
        Mechanic mechanic = containsAny(n, "curl", "extension", "raise", "fly", "kick-back", "kickback")
            ? Mechanic.ISOLATION : Mechanic.COMPOUND;

        List<List<String>> equip;
        if (containsAny(n, "dumbbell")) equip = List.of(List.of("Dumbbells"));
        else if (containsAny(n, "barbell")) equip = List.of(List.of("Barbell"));
        else if (containsAny(n, "kettlebell", "kb")) equip = List.of(List.of("Kettlebells"));
        else if (containsAny(n, "cable")) equip = List.of(List.of("Cable Machine"));
        else if (containsAny(n, "machine")) equip = List.of(List.of("Machine"));
        else equip = List.of();

        BlockType block = timed ? (pattern == MovementPattern.CORE ? BlockType.CORE : BlockType.CARDIO)
            : (mechanic == Mechanic.ISOLATION ? BlockType.ACCESSORY : BlockType.MAIN);
        RepRange rr = timed ? null : new RepRange(8, 12);

        return new Enrichment(
            pattern, primary, List.of(), laterality, mechanic,
            "Auto-described " + name + ".",
            List.of("Brace your core", "Control the tempo"),
            List.of(block), rr, timed, equip);
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String x : needles) {
            if (haystack.contains(x)) return true;
        }
        return false;
    }
}
