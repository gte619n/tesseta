package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.DayResponse;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import java.time.Instant;
import java.time.LocalDate;

public record ScheduledWorkoutResponse(
    // Owning program. scheduledId is only unique within a program (IMPL-17 D1),
    // so cross-program reads (Workout History) need this to address the
    // completion upsert.
    String programId,
    // Owning program + phase titles, resolved for the Workout History view so the
    // client can render program/phase delineation headers without a second lookup.
    // Null on program-scoped responses (calendar, session log) that don't resolve them.
    String programTitle,
    String scheduledId,
    LocalDate date,
    String phaseId,
    String phaseTitle,
    String dayId,
    String dayLabel,
    int weekIndexInPhase,
    boolean isDeload,
    String locationId,
    String locationName,
    ScheduledStatus status,
    DayResponse session,
    Instant completedAt,
    Integer durationSeconds
) {}
