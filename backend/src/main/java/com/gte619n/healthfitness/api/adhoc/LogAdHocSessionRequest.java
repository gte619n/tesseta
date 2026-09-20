package com.gte619n.healthfitness.api.adhoc;

import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.LoggedPrescription;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Body of {@code PUT /api/me/adhoc-workouts/{adhocId}/sessions/{sessionId}}
 * (IMPL-ADHOC-01 AD-05). The terminal upsert: it materializes the run's snapshot
 * from the template the first time it arrives (client-minted {@code sessionId})
 * and records the outcome. Idempotent under outbox replay. No PLANNED start
 * endpoint exists — a run only persists at COMPLETED/SKIPPED.
 */
public record LogAdHocSessionRequest(
    ScheduledStatus status,           // COMPLETED or SKIPPED
    LocalDate date,                   // client-local day the run happened
    Instant completedAt,              // required for COMPLETED
    Integer durationSeconds,          // required for COMPLETED
    List<LoggedPrescription> logged,  // per-prescription actuals (COMPLETED)
    Integer feeling                   // post-workout mood 1..5 (COMPLETED only)
) {}
