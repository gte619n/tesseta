package com.gte619n.healthfitness.core.exercise;

import static com.gte619n.healthfitness.core.exercise.BlockType.ACCESSORY;
import static com.gte619n.healthfitness.core.exercise.BlockType.CARDIO;
import static com.gte619n.healthfitness.core.exercise.BlockType.CORE;
import static com.gte619n.healthfitness.core.exercise.BlockType.MAIN;
import static com.gte619n.healthfitness.core.exercise.BlockType.MOBILITY;
import static com.gte619n.healthfitness.core.exercise.BlockType.STRETCH;
import static com.gte619n.healthfitness.core.exercise.BlockType.WARMUP;
import static com.gte619n.healthfitness.core.exercise.Laterality.BILATERAL;
import static com.gte619n.healthfitness.core.exercise.Laterality.UNILATERAL;
import static com.gte619n.healthfitness.core.exercise.Mechanic.COMPOUND;
import static com.gte619n.healthfitness.core.exercise.Mechanic.ISOLATION;

import java.util.List;

/**
 * A starter set of common movements seeded into the catalog (IMPL-14). Required
 * equipment is expressed by NAME (resolved to catalog ids at seed time;
 * unresolved groups are dropped). Bodyweight movements have no requirements.
 */
public final class ExerciseSeedData {

    private ExerciseSeedData() {}

    private static List<String> g(String... names) { return List.of(names); }

    @SafeVarargs
    private static List<List<String>> req(List<String>... groups) { return List.of(groups); }

    private static ExerciseSeed s(
        String name, MovementPattern pattern, Mechanic mechanic, Laterality lat,
        List<String> primary, List<String> cues, List<BlockType> blocks,
        boolean timed, Integer min, Integer max, List<List<String>> equipment
    ) {
        return new ExerciseSeed(name, pattern, mechanic, lat, primary, List.of(), cues, blocks,
            timed, min, max, equipment);
    }

    public static final List<ExerciseSeed> SEEDS = List.of(
        // ---- Lower: squat / hinge / lunge ----
        s("Barbell Back Squat", MovementPattern.SQUAT, COMPOUND, BILATERAL,
            List.of("quadriceps", "glutes"), List.of("Brace, neutral spine", "Knees track over toes", "Hips below knee level"),
            List.of(MAIN), false, 4, 8, req(g("Barbell"), g("Squat Rack", "Power Rack"))),
        s("Goblet Squat", MovementPattern.SQUAT, COMPOUND, BILATERAL,
            List.of("quadriceps", "glutes"), List.of("Hold the bell at the chest", "Sit between the hips"),
            List.of(MAIN, ACCESSORY), false, 8, 12, req(g("Dumbbells", "Kettlebell"))),
        s("Bodyweight Squat", MovementPattern.SQUAT, COMPOUND, BILATERAL,
            List.of("quadriceps", "glutes"), List.of("Sit back and down", "Chest tall"),
            List.of(WARMUP, MAIN), false, 12, 20, req()),
        s("Romanian Deadlift", MovementPattern.HINGE, COMPOUND, BILATERAL,
            List.of("hamstrings", "glutes"), List.of("Hinge at the hips", "Soft knees, flat back", "Bar close to legs"),
            List.of(MAIN), false, 6, 10, req(g("Barbell"))),
        s("Dumbbell Romanian Deadlift", MovementPattern.HINGE, COMPOUND, BILATERAL,
            List.of("hamstrings", "glutes"), List.of("Hinge at the hips", "Flat back"),
            List.of(MAIN, ACCESSORY), false, 8, 12, req(g("Dumbbells"))),
        s("Walking Lunge", MovementPattern.LUNGE, COMPOUND, UNILATERAL,
            List.of("quadriceps", "glutes"), List.of("Long step", "Front knee over mid-foot"),
            List.of(ACCESSORY), false, 8, 12, req(g("Dumbbells"))),
        s("Leg Press", MovementPattern.SQUAT, COMPOUND, BILATERAL,
            List.of("quadriceps", "glutes"), List.of("Full controlled range", "Don't lock the knees hard"),
            List.of(MAIN, ACCESSORY), false, 8, 15, req(g("Leg Press Machine"))),
        s("Leg Curl", MovementPattern.HINGE, ISOLATION, BILATERAL,
            List.of("hamstrings"), List.of("Squeeze at the top", "Control the negative"),
            List.of(ACCESSORY), false, 10, 15, req(g("Leg Curl Machine"))),
        s("Leg Extension", MovementPattern.SQUAT, ISOLATION, BILATERAL,
            List.of("quadriceps"), List.of("Pause at the top", "Control down"),
            List.of(ACCESSORY), false, 10, 15, req(g("Leg Extension Machine"))),

        // ---- Push: horizontal / vertical ----
        s("Barbell Bench Press", MovementPattern.PUSH_HORIZONTAL, COMPOUND, BILATERAL,
            List.of("chest", "triceps"), List.of("Retract the shoulder blades", "Bar to mid-chest", "Drive feet"),
            List.of(MAIN), false, 4, 8, req(g("Barbell"), g("Flat Bench"))),
        s("Flat Dumbbell Bench Press", MovementPattern.PUSH_HORIZONTAL, COMPOUND, BILATERAL,
            List.of("chest", "triceps"), List.of("Lower to chest level", "Press in a slight arc"),
            List.of(MAIN, ACCESSORY), false, 8, 12, req(g("Dumbbells"), g("Flat Bench", "Adjustable Bench"))),
        s("Push-Up", MovementPattern.PUSH_HORIZONTAL, COMPOUND, BILATERAL,
            List.of("chest", "triceps"), List.of("Rigid plank", "Elbows ~45°"),
            List.of(WARMUP, ACCESSORY), false, 10, 20, req()),
        s("Overhead Press", MovementPattern.PUSH_VERTICAL, COMPOUND, BILATERAL,
            List.of("shoulders", "triceps"), List.of("Brace, glutes tight", "Bar over mid-foot at lockout"),
            List.of(MAIN), false, 5, 8, req(g("Barbell"))),
        s("Dumbbell Shoulder Press", MovementPattern.PUSH_VERTICAL, COMPOUND, BILATERAL,
            List.of("shoulders", "triceps"), List.of("Press overhead", "Don't flare excessively"),
            List.of(MAIN, ACCESSORY), false, 8, 12, req(g("Dumbbells"))),
        s("Cable Triceps Pushdown", MovementPattern.PUSH_VERTICAL, ISOLATION, BILATERAL,
            List.of("triceps"), List.of("Elbows pinned", "Full lockout"),
            List.of(ACCESSORY), false, 10, 15, req(g("Cable Machine"))),

        // ---- Pull: horizontal / vertical ----
        s("Bent-Over Barbell Row", MovementPattern.PULL_HORIZONTAL, COMPOUND, BILATERAL,
            List.of("back", "biceps"), List.of("Hinge ~45°, flat back", "Pull to the lower ribs"),
            List.of(MAIN), false, 6, 10, req(g("Barbell"))),
        s("One-Arm Dumbbell Row", MovementPattern.PULL_HORIZONTAL, COMPOUND, UNILATERAL,
            List.of("back", "biceps"), List.of("Flat back", "Drive the elbow back"),
            List.of(ACCESSORY), false, 8, 12, req(g("Dumbbells"), g("Flat Bench", "Adjustable Bench"))),
        s("Lat Pulldown", MovementPattern.PULL_VERTICAL, COMPOUND, BILATERAL,
            List.of("back", "biceps"), List.of("Pull to the upper chest", "Control the return"),
            List.of(MAIN, ACCESSORY), false, 8, 12, req(g("Lat Pulldown Machine"))),
        s("Pull-Up", MovementPattern.PULL_VERTICAL, COMPOUND, BILATERAL,
            List.of("back", "biceps"), List.of("Full hang to chin over bar", "No kipping"),
            List.of(MAIN, ACCESSORY), false, 5, 10, req(g("Pull-Up Bar"))),
        s("Seated Cable Row", MovementPattern.PULL_HORIZONTAL, COMPOUND, BILATERAL,
            List.of("back", "biceps"), List.of("Tall chest", "Squeeze the blades"),
            List.of(MAIN, ACCESSORY), false, 8, 12, req(g("Cable Machine"))),
        s("Dumbbell Biceps Curl", MovementPattern.PULL_VERTICAL, ISOLATION, BILATERAL,
            List.of("biceps"), List.of("Elbows still", "Control the negative"),
            List.of(ACCESSORY), false, 10, 15, req(g("Dumbbells"))),

        // ---- Core ----
        s("Plank", MovementPattern.CORE, ISOLATION, BILATERAL,
            List.of("core"), List.of("Squeeze glutes and abs", "Neutral neck"),
            List.of(CORE), true, null, null, req()),
        s("Hanging Knee Raise", MovementPattern.CORE, ISOLATION, BILATERAL,
            List.of("core"), List.of("Control, no swing", "Posterior tilt at the top"),
            List.of(CORE), false, 10, 15, req(g("Pull-Up Bar"))),
        s("Cable Crunch", MovementPattern.CORE, ISOLATION, BILATERAL,
            List.of("core"), List.of("Flex the spine", "Hips fixed"),
            List.of(CORE), false, 12, 20, req(g("Cable Machine"))),

        // ---- Cardio (timed) ----
        s("Treadmill Zone 2", MovementPattern.CARDIO, COMPOUND, BILATERAL,
            List.of("cardiovascular"), List.of("Conversational pace", "Nasal breathing if possible"),
            List.of(CARDIO), true, null, null, req(g("Treadmill"))),
        s("Stationary Bike Intervals", MovementPattern.CARDIO, COMPOUND, BILATERAL,
            List.of("cardiovascular"), List.of("Hard effort then easy spin"),
            List.of(CARDIO), true, null, null, req(g("Stationary Bike"))),
        s("Rowing Erg", MovementPattern.CARDIO, COMPOUND, BILATERAL,
            List.of("cardiovascular", "back"), List.of("Legs-hips-arms sequence", "Strong drive, smooth recovery"),
            List.of(CARDIO, WARMUP), true, null, null, req(g("Rowing Machine"))),

        // ---- Warm-up / mobility / stretch (bodyweight) ----
        s("Cat-Cow", MovementPattern.MOBILITY, ISOLATION, BILATERAL,
            List.of("spine"), List.of("Move slowly through the spine"),
            List.of(WARMUP, MOBILITY), true, null, null, req()),
        s("World's Greatest Stretch", MovementPattern.MOBILITY, COMPOUND, UNILATERAL,
            List.of("hips", "thoracic spine"), List.of("Lunge, rotate, reach"),
            List.of(WARMUP, MOBILITY), true, null, null, req()),
        s("Couch Stretch", MovementPattern.STRETCH, ISOLATION, UNILATERAL,
            List.of("hip flexors", "quadriceps"), List.of("Tall posture", "Breathe and relax in"),
            List.of(STRETCH), true, null, null, req())
    );
}
