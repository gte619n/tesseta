package com.gte619n.healthfitness.core.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutBlockSplitter.Section;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkoutBlockSplitterTest {

    private static Prescription rx(String exerciseId, int orderIndex) {
        return new Prescription(exerciseId, orderIndex, 3, null, null, null, null, null, null,
            exerciseId, null, List.of());
    }

    private static WorkoutDay flatDay(Prescription... rxs) {
        return new WorkoutDay("d1", "Logged Day", DayOfWeek.MON, null, 0,
            List.of(new Block("logged", BlockType.MAIN, "Logged", 0, List.of(rxs))));
    }

    @Test
    void isStrengthOnlyForMainOrAccessory() {
        assertTrue(WorkoutBlockSplitter.isStrength(List.of(BlockType.MAIN)));
        assertTrue(WorkoutBlockSplitter.isStrength(List.of(BlockType.ACCESSORY)));
        assertFalse(WorkoutBlockSplitter.isStrength(
            List.of(BlockType.WARMUP, BlockType.MOBILITY, BlockType.COOLDOWN, BlockType.STRETCH)));
        assertFalse(WorkoutBlockSplitter.isStrength(List.of()));
        assertFalse(WorkoutBlockSplitter.isStrength(null));
    }

    @Test
    void splitsIntoWarmupMainCooldownPreservingOrderAndReindexing() {
        // Logged order is scattered: stretch, squat, stretch, lunge, stretch.
        WorkoutDay day = flatDay(rx("stretchA", 0), rx("squat", 1), rx("stretchB", 2),
            rx("lunge", 3), rx("stretchC", 4));
        Map<String, Section> sections = Map.of(
            "stretchA", Section.WARMUP,
            "squat", Section.MAIN,
            "stretchB", Section.MAIN,   // mid-session mobility kept in main
            "lunge", Section.MAIN,
            "stretchC", Section.COOLDOWN);

        List<Block> blocks = WorkoutBlockSplitter.split(day, sections);

        assertEquals(3, blocks.size());
        assertEquals(BlockType.WARMUP, blocks.get(0).type());
        assertEquals("Warm-up", blocks.get(0).title());
        assertEquals(List.of("stretchA"), ids(blocks.get(0)));

        assertEquals(BlockType.MAIN, blocks.get(1).type());
        assertEquals(List.of("squat", "stretchB", "lunge"), ids(blocks.get(1)));
        // re-indexed contiguously within the block
        assertEquals(List.of(0, 1, 2), orders(blocks.get(1)));

        assertEquals(BlockType.COOLDOWN, blocks.get(2).type());
        assertEquals(List.of("stretchC"), ids(blocks.get(2)));
        // block orderIndex is sequential
        assertEquals(0, blocks.get(0).orderIndex());
        assertEquals(1, blocks.get(1).orderIndex());
        assertEquals(2, blocks.get(2).orderIndex());
    }

    @Test
    void omitsEmptySectionsAndDefaultsUnmappedToMain() {
        WorkoutDay day = flatDay(rx("squat", 0), rx("bench", 1));
        // no warm-up / cool-down, and "bench" is unmapped → defaults to Main
        List<Block> blocks = WorkoutBlockSplitter.split(day, Map.of("squat", Section.MAIN));

        assertEquals(1, blocks.size());
        assertEquals(BlockType.MAIN, blocks.get(0).type());
        assertEquals(List.of("squat", "bench"), ids(blocks.get(0)));
    }

    @Test
    void flattensInLoggedOrderAcrossInputBlockAndPrescriptionOrder() {
        WorkoutDay day = flatDay(rx("a", 2), rx("b", 0), rx("c", 1));
        List<Block> blocks = WorkoutBlockSplitter.split(day,
            Map.of("a", Section.MAIN, "b", Section.MAIN, "c", Section.MAIN));
        // sorted by prescription orderIndex: b(0), c(1), a(2)
        assertEquals(List.of("b", "c", "a"), ids(blocks.get(0)));
    }

    private static List<String> ids(Block b) {
        return b.prescriptions().stream().map(Prescription::exerciseId).toList();
    }

    private static List<Integer> orders(Block b) {
        return b.prescriptions().stream().map(Prescription::orderIndex).toList();
    }
}
