package com.gte619n.healthfitness.core.goals.eval;

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
}
