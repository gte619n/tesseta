package com.gte619n.healthfitness.api.biometrics;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import java.util.Optional;

// The set of biometrics the dashboard can show/hide. Each carries a stable key
// (its enum name, used in the hiddenBiometrics pref + the API), a display label,
// a canonical unit for the raw latest value (clients format for the user's unit
// preference), and — for body-composition metrics — the underlying
// BodyCompositionMetric. Metrics with a null bodyMetric are daily metrics
// (sourced from the dailyMetrics series).
public enum Biometric {
    WEIGHT("Weight", "kg", BodyCompositionMetric.WEIGHT_KG),
    BODY_FAT("Body fat", "%", BodyCompositionMetric.BODY_FAT_PERCENT),
    SLEEP("Sleep", "min", null),
    STEPS("Steps", "", null),
    RESTING_HR("Resting HR", "bpm", null),
    HRV("HRV", "ms", null);

    private final String label;
    private final String unit;
    private final BodyCompositionMetric bodyMetric;

    Biometric(String label, String unit, BodyCompositionMetric bodyMetric) {
        this.label = label;
        this.unit = unit;
        this.bodyMetric = bodyMetric;
    }

    public String label() {
        return label;
    }

    public String unit() {
        return unit;
    }

    /** Non-null for body-composition metrics (weight, body fat); null for daily metrics. */
    public BodyCompositionMetric bodyMetric() {
        return bodyMetric;
    }

    public boolean isBody() {
        return bodyMetric != null;
    }

    /** Lenient lookup by key (enum name); empty for an unknown key. */
    public static Optional<Biometric> tryFrom(String key) {
        if (key == null) return Optional.empty();
        for (Biometric b : values()) {
            if (b.name().equals(key)) return Optional.of(b);
        }
        return Optional.empty();
    }
}
