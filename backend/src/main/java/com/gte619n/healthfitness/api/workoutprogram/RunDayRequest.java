package com.gte619n.healthfitness.api.workoutprogram;

import java.time.LocalDate;

/**
 * Body of the ad-hoc "run this day now" materialization
 * ({@code POST /api/me/workout-programs/{programId}/sessions}): pick any day out
 * of the program's phases and get a {@link com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout}
 * dated {@code date} (default: today) so it can be started and logged like any
 * scheduled session — even after the program's original window has passed.
 * Idempotent: the session id is {@code "{date}_{dayId}"}, so re-running the same
 * day on the same date reuses the existing row.
 */
public record RunDayRequest(
    String phaseId,
    String dayId,
    LocalDate date   // optional; null → today (resolved server-side)
) {}
