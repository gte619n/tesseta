package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.exercise.BlockType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits a flat workout day (e.g. the imported-history "Logged" block) into
 * Warm-up / Main / Cool-down blocks, grouping each exercise by an assigned
 * {@link Section} while preserving its relative order within the section.
 *
 * <p>Division of labour (see the import block-split job): the
 * <b>deterministic</b> part is which movements are working sets — anything whose
 * {@link BlockType} suitability includes {@code MAIN} or {@code ACCESSORY} is
 * {@link Section#MAIN}. The genuinely ambiguous part — whether a given
 * stretch/mobility move is a warm-up or a cool-down — is decided upstream
 * (Gemini best-effort, since the logged order is not warm-up→main→cool-down) and
 * supplied via the section map. Pure and deterministic given that map.
 *
 * <p>Since the source workouts contain no supersets, regrouping mobility out of
 * the middle of the session does not break any intended pairing.
 */
public final class WorkoutBlockSplitter {

    private WorkoutBlockSplitter() {}

    public enum Section { WARMUP, MAIN, COOLDOWN }

    /** Block types that unambiguously mark a working set (→ {@link Section#MAIN}). */
    private static final Set<BlockType> STRENGTH = EnumSet.of(BlockType.MAIN, BlockType.ACCESSORY);

    /**
     * True when an exercise's {@code suitableBlockTypes} unambiguously marks it a
     * working set, so it can be placed in Main without a model. Everything else
     * (stretch / mobility / warm-up / cool-down / cardio / core-only, or unknown)
     * is left for the caller to classify.
     */
    public static boolean isStrength(List<BlockType> suitableBlockTypes) {
        if (suitableBlockTypes == null) return false;
        for (BlockType t : suitableBlockTypes) {
            if (STRENGTH.contains(t)) return true;
        }
        return false;
    }

    /**
     * Rebuild {@code day}'s blocks as up-to-three Warm-up / Main / Cool-down
     * blocks. Every prescription across the day's existing blocks is taken in
     * order, bucketed by {@code sectionByExerciseId} (defaulting to {@link
     * Section#MAIN} when an id is absent), and re-emitted with fresh, contiguous
     * {@code orderIndex}es. Empty sections are omitted. Returns the new block
     * list (the day itself is not mutated — records are immutable).
     */
    public static List<Block> split(WorkoutDay day, Map<String, Section> sectionByExerciseId) {
        List<Prescription> warmup = new ArrayList<>();
        List<Prescription> main = new ArrayList<>();
        List<Prescription> cooldown = new ArrayList<>();

        for (Prescription rx : orderedPrescriptions(day)) {
            Section s = sectionByExerciseId.getOrDefault(rx.exerciseId(), Section.MAIN);
            switch (s) {
                case WARMUP -> warmup.add(rx);
                case COOLDOWN -> cooldown.add(rx);
                default -> main.add(rx);
            }
        }

        List<Block> blocks = new ArrayList<>(3);
        int order = 0;
        if (!warmup.isEmpty()) blocks.add(block("warmup", BlockType.WARMUP, "Warm-up", order++, warmup));
        if (!main.isEmpty()) blocks.add(block("main", BlockType.MAIN, "Main", order++, main));
        if (!cooldown.isEmpty()) blocks.add(block("cooldown", BlockType.COOLDOWN, "Cool-down", order++, cooldown));
        return blocks;
    }

    /** All of a day's prescriptions, block order then prescription order. */
    public static List<Prescription> orderedPrescriptions(WorkoutDay day) {
        List<Prescription> out = new ArrayList<>();
        if (day == null || day.blocks() == null) return out;
        day.blocks().stream()
            .sorted((a, b) -> Integer.compare(a.orderIndex(), b.orderIndex()))
            .forEach(b -> {
                if (b.prescriptions() == null) return;
                b.prescriptions().stream()
                    .sorted((a, c) -> Integer.compare(a.orderIndex(), c.orderIndex()))
                    .forEach(out::add);
            });
        return out;
    }

    private static Block block(String id, BlockType type, String title, int orderIndex, List<Prescription> rxs) {
        List<Prescription> reindexed = new ArrayList<>(rxs.size());
        for (int i = 0; i < rxs.size(); i++) {
            Prescription rx = rxs.get(i);
            reindexed.add(new Prescription(
                rx.exerciseId(), i, rx.sets(), rx.repsMin(), rx.repsMax(), rx.durationSeconds(),
                rx.intensity(), rx.restSeconds(), rx.tempo(), rx.notes(), rx.deloadModifier(),
                rx.loggedSets()));
        }
        return new Block(id, type, title, orderIndex, reindexed);
    }
}
