package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.LoggedPrescription;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Body of the ADR-0012 completion upsert
 * ({@code PUT /api/me/workout-programs/{programId}/sessions/{scheduledId}}):
 * a session's outcome plus the per-prescription actuals. Prescriptions have no
 * id, so {@code logged} entries key by {@code (blockId, orderIndex)} against
 * the session snapshot. Retried deliveries from the offline outbox are safe —
 * a repeat PUT replaces actuals and re-runs the fan-out.
 *
 * <p>{@code phaseId}/{@code dayId}/{@code date} are the optional day reference
 * of an offline-first ad-hoc session (run-as-today): the client mints the row
 * locally with the shared {@code "{date}_{dayId}"} id and the server first sees
 * it here, so when all three are present and the session was never materialized
 * the PUT materializes it before applying the outcome. Absent (older clients,
 * normal scheduled sessions) the PUT behaves exactly as before — 404 on an
 * unknown session.
 */
public record LogSessionRequest(
    ScheduledStatus status,           // COMPLETED, SKIPPED, or PLANNED (un-log/reset)
    Instant completedAt,              // required for COMPLETED
    Integer durationSeconds,          // required for COMPLETED
    List<LoggedPrescription> logged,  // full replacement of previous actuals
    String phaseId,                   // ad-hoc materialization day reference…
    String dayId,                     // …
    LocalDate date                    // …the client-local day the session ran
) {
    /** Back-compat: an outcome for an already-materialized session. */
    public LogSessionRequest(
        ScheduledStatus status, Instant completedAt, Integer durationSeconds,
        List<LoggedPrescription> logged
    ) {
        this(status, completedAt, durationSeconds, logged, null, null, null);
    }
}
