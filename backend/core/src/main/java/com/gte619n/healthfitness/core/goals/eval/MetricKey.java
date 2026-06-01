package com.gte619n.healthfitness.core.goals.eval;

import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;

/**
 * The closed set of metric keys the Step evaluator understands.
 *
 * Each enum constant carries the dotted string used in Firestore
 * documents (e.g. {@code "blood.ldl"}) — that's what Step
 * {@link com.gte619n.healthfitness.core.goals.StepMetricBinding}
 * documents persist. The enum is the in-memory representation used by
 * the resolver and the evaluator.
 *
 * Adding a new metric is a two-step change: add an enum constant here,
 * and add a branch in {@link FirestoreMetricResolver}. Anything else
 * (REST DTOs, AI prompts) reads the registry through this enum.
 */
public enum MetricKey {
    BODY_WEIGHT("body.weight"),
    BODY_BODY_FAT_PCT("body.bodyFatPct"),
    BODY_LEAN_MASS("body.leanMass"),
    BLOOD_LDL("blood.ldl"),
    BLOOD_APOB("blood.apoB"),
    BLOOD_HBA1C("blood.hba1c"),
    BLOOD_HS_CRP("blood.hsCRP"),
    VITALS_RESTING_HR("vitals.restingHr"),
    VITALS_HRV("vitals.hrv"),
    VITALS_SLEEP_SCORE("vitals.sleepScore"),
    WORKOUTS_COUNT("workouts.count"),
    WORKOUTS_WEEKLY_VOLUME("workouts.weeklyVolume"),
    NUTRITION_PROTEIN_AVG_7D("nutrition.proteinAvg7d"),
    NUTRITION_CARBS_AVG_7D("nutrition.carbsAvg7d"),
    NUTRITION_FAT_AVG_7D("nutrition.fatAvg7d"),
    NUTRITION_CALORIES_AVG_7D("nutrition.caloriesAvg7d"),
    NUTRITION_FIBER_AVG_7D("nutrition.fiberAvg7d"),
    NUTRITION_SUGAR_AVG_7D("nutrition.sugarAvg7d"),
    NUTRITION_TARGET_MET_DAYS("nutrition.targetMetDays"),
    MEDS_ADHERENCE_30D("meds.adherence30d");

    private final String key;

    MetricKey(String key) {
        this.key = key;
    }

    /** The dotted string form persisted on {@code StepMetricBinding.metricKey}. */
    public String key() {
        return key;
    }

    /**
     * Look up a {@link MetricKey} by its dotted string form.
     *
     * Returns {@code null} (rather than throwing) for unknown strings
     * because Step bindings can carry stale or hand-edited keys; the
     * evaluator must degrade to "no-op" in that case, not crash the
     * whole evaluation pass.
     */
    public static MetricKey fromKey(String key) {
        if (key == null) return null;
        for (MetricKey k : values()) {
            if (k.key.equals(key)) {
                return k;
            }
        }
        return null;
    }

    /**
     * Map a {@link BloodMarker} to its {@link MetricKey} equivalent.
     *
     * Only markers that have an entry in the Goals metric registry are
     * mapped. Markers we track for clinical context (HDL, triglycerides,
     * total cholesterol, fasting glucose) are not Goals metrics today —
     * return {@code null} so callers can skip publishing.
     */
    public static MetricKey fromBloodMarker(BloodMarker marker) {
        if (marker == null) return null;
        return switch (marker) {
            case LDL -> BLOOD_LDL;
            case APO_B -> BLOOD_APOB;
            case HBA1C -> BLOOD_HBA1C;
            case HS_CRP -> BLOOD_HS_CRP;
            default -> null; // HDL, TRIGLYCERIDES, TOTAL_CHOLESTEROL, FASTING_GLUCOSE
        };
    }

    /**
     * Map a {@link BodyCompositionMetric} to its {@link MetricKey} equivalent.
     *
     * BMI is not in the Goals metric registry — return {@code null} so
     * callers can skip publishing.
     */
    public static MetricKey fromBodyCompositionMetric(BodyCompositionMetric metric) {
        if (metric == null) return null;
        return switch (metric) {
            case WEIGHT_KG -> BODY_WEIGHT;
            case BODY_FAT_PERCENT -> BODY_BODY_FAT_PCT;
            case LEAN_MASS_KG -> BODY_LEAN_MASS;
            default -> null; // BMI
        };
    }
}
