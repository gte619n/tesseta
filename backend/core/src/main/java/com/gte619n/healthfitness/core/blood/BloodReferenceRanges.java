package com.gte619n.healthfitness.core.blood;

import java.util.Map;

// Canonical reference ranges for adult general population. These are
// rough guideposts (not medical advice) chosen from common clinical
// thresholds — ACC/AHA for lipids, ADA for glycemic, etc. Per-user
// overrides aren't supported yet.
//
// Each range carries: canonical display unit, "good below this value",
// and a display max for the visualization bar. tone direction varies
// per marker — for HDL "higher is better"; for everything else "lower
// is better". The orientation field encodes that.
public final class BloodReferenceRanges {

    public enum Orientation { LOWER_IS_BETTER, HIGHER_IS_BETTER }

    public record Range(
        String unit,
        Orientation orientation,
        double goodThreshold,   // good when value is on the good-side of this
        double displayMin,      // lower bound of the marker viz scale
        double displayMax       // upper bound
    ) {}

    private BloodReferenceRanges() {}

    public static final Map<BloodMarker, Range> RANGES = Map.of(
        BloodMarker.TOTAL_CHOLESTEROL,
        new Range("mg/dL", Orientation.LOWER_IS_BETTER, 200, 0, 300),
        BloodMarker.LDL,
        new Range("mg/dL", Orientation.LOWER_IS_BETTER, 100, 0, 200),
        BloodMarker.HDL,
        new Range("mg/dL", Orientation.HIGHER_IS_BETTER, 60, 0, 100),
        BloodMarker.TRIGLYCERIDES,
        new Range("mg/dL", Orientation.LOWER_IS_BETTER, 150, 0, 300),
        BloodMarker.APO_B,
        new Range("mg/dL", Orientation.LOWER_IS_BETTER, 90, 0, 180),
        BloodMarker.HBA1C,
        new Range("%", Orientation.LOWER_IS_BETTER, 5.7, 4, 7),
        BloodMarker.FASTING_GLUCOSE,
        new Range("mg/dL", Orientation.LOWER_IS_BETTER, 100, 60, 140),
        BloodMarker.HS_CRP,
        new Range("mg/L", Orientation.LOWER_IS_BETTER, 1.0, 0, 3),
        BloodMarker.TESTOSTERONE,
        new Range("ng/dL", Orientation.HIGHER_IS_BETTER, 300, 200, 1200)
        // NOTE: 9 entries — Map.of caps at 10 key/value pairs. A 10th+ marker
        // must switch this literal to Map.ofEntries(Map.entry(...), ...).
    );

    public static Range rangeFor(BloodMarker marker) {
        Range r = RANGES.get(marker);
        if (r == null) {
            throw new IllegalArgumentException("No reference range for " + marker);
        }
        return r;
    }
}
