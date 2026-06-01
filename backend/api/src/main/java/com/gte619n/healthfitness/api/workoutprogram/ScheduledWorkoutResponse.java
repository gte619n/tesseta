package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.api.workoutprogram.WorkoutProgramDeepResponse.DayResponse;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import java.time.LocalDate;

public record ScheduledWorkoutResponse(
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
    DayResponse session
) {}
