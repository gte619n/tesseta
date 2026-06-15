package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.workoutprogram.NutritionGuidance;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSchedule;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import java.time.LocalDate;
import java.util.List;

public record CreateProgramRequest(
    String title,
    String description,
    String goalId,
    ProgramSchedule schedule,
    LocalDate startDate,
    ProgramSource source,
    List<ProgramPhase> phases,
    NutritionGuidance nutritionGuidance  // IMPL-18: program-level fallback; phases may also carry their own
) {}
