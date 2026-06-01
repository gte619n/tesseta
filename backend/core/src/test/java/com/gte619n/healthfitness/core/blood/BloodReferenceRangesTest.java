package com.gte619n.healthfitness.core.blood;

import static org.assertj.core.api.Assertions.assertThat;

import com.gte619n.healthfitness.core.blood.BloodReferenceRanges.Orientation;
import com.gte619n.healthfitness.core.blood.BloodReferenceRanges.Range;
import org.junit.jupiter.api.Test;

class BloodReferenceRangesTest {

    @Test
    void testosteroneRangeMatchesSpec() {
        Range range = BloodReferenceRanges.rangeFor(BloodMarker.TESTOSTERONE);

        assertThat(range).isNotNull();
        assertThat(range.unit()).isEqualTo("ng/dL");
        assertThat(range.orientation()).isEqualTo(Orientation.HIGHER_IS_BETTER);
        assertThat(range.goodThreshold()).isEqualTo(300);
        assertThat(range.displayMin()).isEqualTo(200);
        assertThat(range.displayMax()).isEqualTo(1200);
    }

    @Test
    void everyMarkerHasARange() {
        // Guards Map.of from silently missing a future addition: every enum
        // value must resolve to a non-null range (rangeFor throws otherwise).
        for (BloodMarker marker : BloodMarker.values()) {
            assertThat(BloodReferenceRanges.rangeFor(marker))
                .as("range for %s", marker)
                .isNotNull();
        }
    }
}
