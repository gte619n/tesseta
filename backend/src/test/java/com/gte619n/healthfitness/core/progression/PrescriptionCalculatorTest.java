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
            state(200, 4), new RepBand(6, 8), 2, 0, 5, 150, 145, false, 3);
        assertEquals(150, p.targetWeightLbs(), 1e-9);
    }

    @Test
    void speculativeCapLimitsBigSingleDayGainsToOneIncrement() {
        // Belief jumped high but the lifter was on-plan (perf ≈ prescribed 100):
        // cap to +1 real increment → 105 (IMPL-PROG-LOAD-01 IL-7), not 400-based.
        PrescribedLoad p = PrescriptionCalculator.calculate(
            state(400, 8), new RepBand(6, 8), 2, 0, 5, 100, 100, false, 3);
        assertEquals(105, p.targetWeightLbs(), 1e-9, "capped to +1 increment");
    }

    @Test
    void lowConfidenceWidensRangeAndEasesLoad() {
        // sigma/e1rm = 10% > 6% → band widens by ±1 and load drops.
        PrescribedLoad wide = PrescriptionCalculator.calculate(
            state(200, 20), new RepBand(6, 8), 2, 0, 5, 300, 145, false, 3);
        assertEquals(5, wide.repsMin());  // 6-1
        assertEquals(9, wide.repsMax());  // 8+1
    }

    @Test
    void incrementFloorHoldsLoadAndAddsARep() {
        // Computed load equals last performed → hold, signal a rep advance.
        PrescribedLoad p = PrescriptionCalculator.calculate(
            state(200, 4), new RepBand(6, 8), 2, 0, 5, 150, 150, false, 3);
        assertEquals(150, p.targetWeightLbs(), 1e-9);
        assertEquals(Direction.HOLD, p.rationale().direction());
        assertEquals(Integer.valueOf(1), p.rationale().deltaReps());
    }

    // ---- IMPL-PROG-LOAD-01 P4: demonstrated performance overrides the cap ----

    @Test
    void demonstratedLoadMatchedWhenOutperformedWithReserve() {
        // Prescribed 140, lifter actually pressed 190 with reps in reserve
        // (outperformed=true). The engine matches what they lifted (190), NOT the
        // old +5%-of-140 cap (147). e1rm 250 → raw 192.3, floor-matched to 190.
        PrescribedLoad p = PrescriptionCalculator.calculate(
            state(250, 5), new RepBand(6, 8), 2, 0, 5, 140, 190, true, 3);
        assertEquals(190, p.targetWeightLbs(), 1e-9, "matched demonstrated load, not capped to 147");
        assertTrue(p.targetWeightLbs() > 147, "old +5% cap would have suppressed this");
    }

    @Test
    void grindingHeavierIsNotMatched() {
        // Lifter used 190 > prescribed 140 but at RIR 0 (outperformed=false): the
        // engine does NOT floor up to 190; belief-based load stands (D14/D15).
        PrescribedLoad p = PrescriptionCalculator.calculate(
            state(210, 5), new RepBand(6, 8), 2, 0, 5, 140, 190, false, 3);
        assertTrue(p.targetWeightLbs() < 190, "grinding is not treated as demonstrated capacity");
    }
}
