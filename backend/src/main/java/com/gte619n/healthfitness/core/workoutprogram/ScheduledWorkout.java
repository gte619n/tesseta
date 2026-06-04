package com.gte619n.healthfitness.core.workoutprogram;

import java.time.Instant;
import java.time.LocalDate;

/**
 * A concrete dated instance of a Workout Day, produced when a program is
 * activated. {@code session} is a denormalized snapshot of the day's plan at
 * materialization time so editing the template later doesn't rewrite past
 * sessions.
 *
 * <p>{@code completedAt} and {@code durationSeconds} are populated only for
 * performed sessions (e.g. the IMPL-15 history import), where they carry the
 * wall-clock finish time and elapsed duration the Workout History view shows.
 * They are null for PLANNED sessions materialized from a template.
 */
public record ScheduledWorkout(
    String userId,
    String programId,
    String scheduledId,             // "{date}_{dayId}"
    LocalDate date,
    String phaseId,
    String dayId,
    String dayLabel,
    int weekIndexInPhase,           // 1-based
    boolean isDeload,
    String locationId,
    ScheduledStatus status,
    WorkoutDay session,             // snapshot
    Instant completedAt,            // when the session was performed (history only)
    Integer durationSeconds         // elapsed workout time (history only)
) {}
