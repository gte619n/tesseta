package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** Golden tests for the pure progression math (IMPL-PROG-01 §6, §9.1). */
class ProgressionMathTest {

    @Test
    void epleyMatchesKnownValue() {
        // 100 lb × 10 reps (RIR 0) → 100·(1+10/30) = 133.33
        assertEquals(133.333, ProgressionMath.epleyE1rm(100, 10), 0.01);
    }

    @Test
    void targetPercentInvertsEpley() {
        // At 5 reps + 2 RIR: 1/(1+7/30) = 0.8108
        assertEquals(0.8108, ProgressionMath.targetPercent(5, 2), 0.001);
    }

    @Test
    void rawLoadRoundTripsThroughEpley() {
        double e1rm = 200;
        double load = ProgressionMath.rawLoad(e1rm, 5, 2, 0);
        // Doing `load` for 5 reps + 2 RIR should imply back ~200.
        assertEquals(200, ProgressionMath.epleyE1rm(load, 7), 0.01);
    }

    @Test
    void floorToIncrementRoundsDown() {
        assertEquals(180, ProgressionMath.floorToIncrement(183.9, 5), 1e-9);
        assertEquals(180, ProgressionMath.floorToIncrement(180.0, 5), 1e-9);
        assertEquals(0, ProgressionMath.floorToIncrement(-3, 5), 1e-9);
    }

    @Test
    void confidenceBandsFromRelativeUncertainty() {
        assertEquals(Confidence.HIGH, ProgressionMath.confidenceOf(200, 4));   // 2%
        assertEquals(Confidence.MEDIUM, ProgressionMath.confidenceOf(200, 10)); // 5%
        assertEquals(Confidence.LOW, ProgressionMath.confidenceOf(200, 20));    // 10%
        assertEquals(Confidence.LOW, ProgressionMath.confidenceOf(0, 0));
    }

    @Test
    void wideningTriggersAboveSixPercent() {
        assertTrue(ProgressionMath.shouldWiden(200, 13));   // 6.5%
        assertFalse(ProgressionMath.shouldWiden(200, 10));  // 5%
    }

    @Test
    void noiseModelPinsFactors() {
        assertEquals(1.0, ObservationNoiseModel.fReps(6), 1e-9);
        assertEquals(1.3, ObservationNoiseModel.fReps(9), 1e-9);
        assertEquals(2.0, ObservationNoiseModel.fReps(13), 1e-9);
        assertEquals(3.5, ObservationNoiseModel.fReps(20), 1e-9);
        assertEquals(1.0, ObservationNoiseModel.fSetPosition(true), 1e-9);
        assertEquals(1.8, ObservationNoiseModel.fSetPosition(false), 1e-9);
        // f_context caps at 3.0
        assertEquals(3.0, ObservationNoiseModel.fContext(
            Set.of(ContextFlag.POOR_SLEEP, ContextFlag.ILLNESS, ContextFlag.DEFICIT)), 1e-9);
        // REPORTED scales with RIR magnitude: 1.2·(1+0.15·2) = 1.56
        assertEquals(1.56, ObservationNoiseModel.fSource(RirSource.REPORTED, 2.0), 1e-9);
    }

    @Test
    void absentRirYieldsInfiniteNoiseSoCallerSkips() {
        assertTrue(Double.isInfinite(ObservationNoiseModel.fSource(RirSource.ABSENT, null)));
    }

    @Test
    void kalmanGainMovesBeliefTowardObservation() {
        // Prior 200±10, observe 220 with sd 10 → belief moves up but not all the way.
        KalmanUpdate.Result r = KalmanUpdate.step(200, 10, 0, 1, 220, 10);
        assertTrue(r.e1rmLbs() > 200 && r.e1rmLbs() < 220);
        assertTrue(r.sigmaLbs() < 10); // more confident after an observation
    }

    @Test
    void kalmanUncertaintyGrowsOverALayoff() {
        double s0 = 5;
        double s30 = KalmanUpdate.driftSigma(200, s0, 30);
        assertTrue(s30 > s0, "sigma should grow across a 30-day gap");
    }
}
