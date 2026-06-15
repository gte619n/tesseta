package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.exercise.BlockType;
import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.DeloadModifier;
import com.gte619n.healthfitness.core.workoutprogram.Intensity;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.NutritionGuidance;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Deep response with the full tree and embedded exercise summaries. */
public record WorkoutProgramDeepResponse(
    String programId,
    String title,
    String description,
    String goalId,
    String goalTitle,
    ProgramStatus status,
    ProgramSource source,
    LocalDate startDate,
    List<DayOfWeek> trainingDays,
    List<PhaseResponse> phases,
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    NutritionGuidance nutritionGuidance  // IMPL-18: program-level fallback when a phase carries none
) {
    public record PhaseResponse(
        String phaseId,
        String title,
        String focus,
        int orderIndex,
        ProgramPhaseStatus status,
        int weeks,
        Integer deloadWeekIndex,
        LocalDate targetStartDate,
        LocalDate targetEndDate,
        List<DayResponse> days,
        NutritionGuidance nutritionGuidance  // IMPL-18: per-phase calorie/macro guidance (display-only)
    ) {}

    public record DayResponse(
        String dayId,
        String label,
        DayOfWeek dayOfWeek,
        String locationId,
        String locationName,
        int orderIndex,
        List<BlockResponse> blocks
    ) {}

    public record BlockResponse(
        String blockId,
        BlockType type,
        String title,
        int orderIndex,
        List<PrescriptionResponse> prescriptions
    ) {}

    public record PrescriptionResponse(
        String exerciseId,
        int orderIndex,
        Integer sets,
        Integer repsMin,
        Integer repsMax,
        Integer durationSeconds,
        Intensity intensity,
        Integer restSeconds,
        String tempo,
        String notes,
        DeloadModifier deloadModifier,
        List<LoggedSet> loggedSets,
        ExerciseSummary exercise,
        Double targetWeightLbs,  // IMPL-18: concrete prescribed load; null → use intensity
        String loadBasis         // IMPL-18: short "why" for the load (e1RM / last done / ramp), shown on tap
    ) {}
}
