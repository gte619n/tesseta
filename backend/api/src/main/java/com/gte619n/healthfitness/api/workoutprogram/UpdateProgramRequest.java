package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import java.time.LocalDate;
import java.util.List;

/** Null fields are left unchanged. */
public record UpdateProgramRequest(
    String title,
    String description,
    String goalId,
    ProgramSchedule schedule,
    LocalDate startDate,
    ProgramStatus status,
    List<ProgramPhase> phases
) {}
