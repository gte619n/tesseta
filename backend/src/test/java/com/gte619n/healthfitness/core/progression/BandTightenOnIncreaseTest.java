package com.gte619n.healthfitness.core.progression;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** IMPL-PROG-02 D5/IMPL-D11: the stored band collapses toward the bottom on a load increase. */
class BandTightenOnIncreaseTest {

    @Test
    void increaseCollapsesBandTowardBottom() {
        PrescribedLoad out = SessionLoop.tightenBandOnIncrease(load(6, 10, Direction.UP));
        assertThat(out.repsMin()).isEqualTo(6);
        assertThat(out.repsMax()).isEqualTo(7); // 6..10 → 6..7
    }

    @Test
    void holdKeepsTheFullBand() {
        PrescribedLoad out = SessionLoop.tightenBandOnIncrease(load(6, 10, Direction.HOLD));
        assertThat(out.repsMin()).isEqualTo(6);
        assertThat(out.repsMax()).isEqualTo(10);
    }

    @Test
    void decreaseKeepsTheFullBand() {
        PrescribedLoad out = SessionLoop.tightenBandOnIncrease(load(6, 10, Direction.DOWN));
        assertThat(out.repsMax()).isEqualTo(10);
    }

    @Test
    void alreadyTightBandIsUnchanged() {
        PrescribedLoad out = SessionLoop.tightenBandOnIncrease(load(6, 6, Direction.UP));
        assertThat(out.repsMin()).isEqualTo(6);
        assertThat(out.repsMax()).isEqualTo(6);
    }

    private static PrescribedLoad load(int min, int max, Direction dir) {
        PrescriptionRationale r = new PrescriptionRationale(
            ProgressionPath.KALMAN, dir, 5.0, null, null, Confidence.HIGH, List.of("test"));
        return new PrescribedLoad(3, min, max, 155.0, r);
    }
}
