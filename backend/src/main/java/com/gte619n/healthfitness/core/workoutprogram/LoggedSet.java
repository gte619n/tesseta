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
 */
public record LoggedSet(
    Double weightLbs,
    Integer reps,
    Double rpe,
    Integer restSeconds,
    Instant completedAt,
    Integer durationSeconds
) {
    /**
     * Pre-timed-logging signature. Delegates with {@code durationSeconds} null so
     * existing callers (importer, completion service, tests) compile unchanged.
     */
    public LoggedSet(Double weightLbs, Integer reps, Double rpe, Integer restSeconds, Instant completedAt) {
        this(weightLbs, reps, rpe, restSeconds, completedAt, null);
    }
}
