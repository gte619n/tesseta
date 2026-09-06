package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Golden tests for deterministic double progression (IMPL-PROG-01 §6.6, §9.1). */
class DoubleProgressionTest {

    private static final RepBand BAND = new RepBand(6, 8);

    @Test
    void allSetsAtTopAddsOneIncrementAndResetsToBottom() {
        PrescribedLoad p = DoubleProgression.next(135, List.of(8, 8, 8), BAND, 5, false, 3);
        assertEquals(140, p.targetWeightLbs(), 1e-9);
        assertEquals(6, p.repsMin());
        assertEquals(8, p.repsMax());
        assertEquals(Direction.UP, p.rationale().direction());
    }

    @Test
    void inRangeHoldsLoad() {
        PrescribedLoad p = DoubleProgression.next(135, List.of(7, 7, 6), BAND, 5, false, 3);
        assertEquals(135, p.targetWeightLbs(), 1e-9);
        assertEquals(Direction.HOLD, p.rationale().direction());
    }

    @Test
    void firstFailureHoldsToRetry() {
        PrescribedLoad p = DoubleProgression.next(135, List.of(5, 6, 6), BAND, 5, false, 3);
        assertEquals(135, p.targetWeightLbs(), 1e-9);
        assertEquals(Direction.HOLD, p.rationale().direction());
    }

    @Test
    void secondConsecutiveFailureDeloadsOneIncrement() {
        PrescribedLoad p = DoubleProgression.next(135, List.of(5, 5, 4), BAND, 5, true, 3);
        assertEquals(130, p.targetWeightLbs(), 1e-9);
        assertEquals(Direction.DOWN, p.rationale().direction());
    }

    @Test
    void deloadNeverGoesNegative() {
        PrescribedLoad p = DoubleProgression.next(3, List.of(2), BAND, 5, true, 3);
        assertEquals(0, p.targetWeightLbs(), 1e-9);
    }

    @Test
    void dumbbellFivePoundJumpUsesTheIncrement() {
        // Fixed-dumbbell case (§6.5): 20 lb press, top hit → +5 lb (not a %).
        PrescribedLoad p = DoubleProgression.next(20, List.of(8, 8), BAND, 5, false, 2);
        assertEquals(25, p.targetWeightLbs(), 1e-9);
    }
}
