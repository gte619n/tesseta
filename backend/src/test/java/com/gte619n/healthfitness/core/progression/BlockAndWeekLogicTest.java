package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Golden tests for block mode selection and week-loop trend/fatigue (§7, §8, §9.1). */
class BlockAndWeekLogicTest {

    @Test
    void modeSelectionFromEnergyBalance() {
        assertEquals(BlockMode.GAINING, BlockLoop.modeFor(400));       // surplus
        assertEquals(BlockMode.RECOMP, BlockLoop.modeFor(0));          // maintenance
        assertEquals(BlockMode.MAINTENANCE, BlockLoop.modeFor(-400));  // deficit 300–500
        assertEquals(BlockMode.RECOVERY, BlockLoop.modeFor(-700));     // deficit >500 (Murphy & Koehler gate)
    }

    @Test
    void trendClassification() {
        assertEquals(Trend.RISING, WeekLoop.classify(0.01));
        assertEquals(Trend.FLAT, WeekLoop.classify(0.0));
        assertEquals(Trend.FALLING, WeekLoop.classify(-0.01));
        assertEquals(Trend.UNKNOWN, WeekLoop.classify(Double.NaN));
    }

    @Test
    void olsSlopeIsCorrect() {
        // y = 2x → slope 2.
        double s = WeekLoop.olsSlope(List.of(new double[]{0, 0}, new double[]{1, 2}, new double[]{2, 4}));
        assertEquals(2.0, s, 1e-9);
    }

    @Test
    void fatigueIndexIsNegativeWhenRepsFallAcrossSets() {
        // Same session/exercise, reps 8→7→6 across set indices → negative slope.
        String sess = "s1", ex = "e1";
        List<SetObservation> obs = List.of(
            obs(sess, ex, 1, 8), obs(sess, ex, 2, 7), obs(sess, ex, 3, 6));
        double fatigue = WeekLoop.fatigueIndex(obs);
        assertTrue(fatigue < 0, "reps falling across sets ⇒ fatigue < 0");
    }

    private static SetObservation obs(String session, String ex, int idx, int reps) {
        return new SetObservation("o" + idx, "u", session, ex, idx, idx == 3,
            135, reps, RirSource.REPORTED, 2.0, null, Instant.EPOCH.plusSeconds(idx), Set.of());
    }
}
