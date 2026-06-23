package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.LoggedPrescription;
import java.time.Instant;
import java.util.List;

/**
 * Body of the ADR-0012 completion upsert
 * ({@code PUT /api/me/workout-programs/{programId}/sessions/{scheduledId}}):
 * a session's outcome plus the per-prescription actuals. Prescriptions have no
 * id, so {@code logged} entries key by {@code (blockId, orderIndex)} against
 * the session snapshot. Retried deliveries from the offline outbox are safe —
 * a repeat PUT replaces actuals and re-runs the fan-out.
 */
public record LogSessionRequest(
    ScheduledStatus status,           // COMPLETED, SKIPPED, or PLANNED (un-log/reset)
    Instant completedAt,              // required for COMPLETED
    Integer durationSeconds,          // required for COMPLETED
    List<LoggedPrescription> logged   // full replacement of previous actuals
) {}
