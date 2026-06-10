package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.DayResponse;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import java.time.Instant;
import java.time.LocalDate;

public record ScheduledWorkoutResponse(
    // Owning program. scheduledId is only unique within a program (IMPL-16 D1),
    // so cross-program reads (Workout History) need this to address the
    // completion upsert.
    String programId,
    String scheduledId,
    LocalDate date,
    String phaseId,
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
