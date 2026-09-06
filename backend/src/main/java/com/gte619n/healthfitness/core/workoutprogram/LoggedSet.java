package com.gte619n.healthfitness.core.workoutprogram;

import java.time.Instant;

/**
 * One set as actually performed in a completed session — the logged counterpart
 * to the planned {@link Prescription}. {@code weightLbs} is the load lifted
 * (0 for bodyweight); {@code reps} is the count, nullable when the source only
 * tracked weight (e.g. the imported history in IMPL-15).
 *
 * <p>{@code rpe}, {@code restSeconds}, and {@code completedAt} are the full
 * actuals captured by the live logger (ADR-0012 Decision 2). All three are
 * nullable: imported-history rows predate them and the logger keeps everything
 * beyond weight/reps skippable.
 *
 * <p>{@code durationSeconds} is the held time for a timed exercise (stretch /
 * mobility / cardio) — the time-based counterpart to {@code reps}. Null for
 * ordinary rep-based sets.
 *
 * <p>{@code rir}/{@code rirSource} are the progression engine's subjective
 * input (IMPL-PROG-01 D1). Both nullable: legacy/imported rows predate them, in
 * which case the engine reads {@link #effectiveRir()} which converts the legacy
 * {@code rpe} as {@code 10 − rpe} (M2).
 */
public record LoggedSet(
    Double weightLbs,
    Integer reps,
    Double rpe,
    Integer restSeconds,
    Instant completedAt,
    Integer durationSeconds,
    Double rir,
    com.gte619n.healthfitness.core.progression.RirSource rirSource
) {
    /**
     * Pre-timed-logging signature. Delegates with {@code durationSeconds} null so
     * existing callers (importer, completion service, tests) compile unchanged.
     */
    public LoggedSet(Double weightLbs, Integer reps, Double rpe, Integer restSeconds, Instant completedAt) {
        this(weightLbs, reps, rpe, restSeconds, completedAt, null, null, null);
    }

    /**
     * Pre-RIR signature (IMPL-PROG-01 M2). Delegates with {@code rir}/
     * {@code rirSource} null so every existing caller compiles unchanged.
     */
    public LoggedSet(Double weightLbs, Integer reps, Double rpe, Integer restSeconds,
                     Instant completedAt, Integer durationSeconds) {
        this(weightLbs, reps, rpe, restSeconds, completedAt, durationSeconds, null, null);
    }

    /**
     * The engine's subjective input: the explicit {@code rir} when logged,
     * otherwise the legacy RPE converted as {@code 10 − rpe} (the D1 backfill
     * applied lazily on read), or null when neither is present.
     */
    public Double effectiveRir() {
        if (rir != null) return rir;
        if (rpe != null) return 10.0 - rpe;
        return null;
    }
}
