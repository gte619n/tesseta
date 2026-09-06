package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Golden tests for the Kalman prescription path (IMPL-PROG-01 §6.4, §9.1). */
class PrescriptionCalculatorTest {

    private static ProgressionState state(double e1rm, double sigma) {
        return new ProgressionState("u", "e", e1rm, sigma, Instant.EPOCH, 10, 0, Instant.EPOCH, 1);
    }

    @Test
    void prescribesFlooredLoadTowardMidRange() {
        // e1rm 200, band 6-8 (mid 7), RIR 2, increment 5.
        // raw = 200/(1+9/30) = 153.8 → floor 5 → 150.
        PrescribedLoad p = PrescriptionCalculator.calculate(
            state(200, 4), new RepBand(6, 8), 2, 0, 5, 150, 145, 3);
        assertEquals(150, p.targetWeightLbs(), 1e-9);
    }

    @Test
    void jumpCapLimitsBigSingleDayGains() {
        // Belief jumped high; last prescribed 100 → cap at 105 → floor 5 → 105.
        PrescribedLoad p = PrescriptionCalculator.calculate(
            state(400, 8), new RepBand(6, 8), 2, 0, 5, 100, 100, 3);
        assertTrue(p.targetWeightLbs() <= 105 + 1e-9, "jump capped to +5%");
    }

    @Test
    void lowConfidenceWidensRangeAndEasesLoad() {
        // sigma/e1rm = 10% > 6% → band widens by ±1 and load drops.
        PrescribedLoad wide = PrescriptionCalculator.calculate(
            state(200, 20), new RepBand(6, 8), 2, 0, 5, 300, 145, 3);
        assertEquals(5, wide.repsMin());  // 6-1
        assertEquals(9, wide.repsMax());  // 8+1
    }

    @Test
    void incrementFloorHoldsLoadAndAddsARep() {
        // Computed load equals last performed → hold, signal a rep advance.
        PrescribedLoad p = PrescriptionCalculator.calculate(
            state(200, 4), new RepBand(6, 8), 2, 0, 5, 150, 150, 3);
        assertEquals(150, p.targetWeightLbs(), 1e-9);
        assertEquals(Direction.HOLD, p.rationale().direction());
        assertEquals(Integer.valueOf(1), p.rationale().deltaReps());
    }
}
