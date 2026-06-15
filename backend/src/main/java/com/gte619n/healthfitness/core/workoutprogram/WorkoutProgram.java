package com.gte619n.healthfitness.core.workoutprogram;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A periodized training plan (IMPL-15). The full Phase → Day → Block →
 * Prescription tree is embedded in {@code phases} (the weekly template is small
 * and bounded). Materialized dated sessions live in a separate
 * {@code ScheduledWorkout} collection.
 */
public record WorkoutProgram(
    String userId,
    String programId,
    String title,
    String description,
    String goalId,                  // nullable link to a Goal
    ProgramStatus status,
    ProgramSource source,
    LocalDate startDate,
    ProgramSchedule schedule,
    List<String> phaseOrder,        // ordered phaseIds — source of truth for sequence
    List<ProgramPhase> phases,      // empty in shallow list responses
    Instant createdAt,
    Instant updatedAt,
    Instant completedAt,
    NutritionGuidance nutritionGuidance  // IMPL-18: program-level nutrition fallback when phases carry none; null = none
) {
    /**
     * Pre-IMPL-18 canonical signature; delegates with no program-level nutrition
     * guidance so existing callers compile unchanged (IMPL-18 D2).
     */
    public WorkoutProgram(
        String userId, String programId, String title, String description, String goalId,
        ProgramStatus status, ProgramSource source, LocalDate startDate, ProgramSchedule schedule,
        List<String> phaseOrder, List<ProgramPhase> phases, Instant createdAt, Instant updatedAt,
        Instant completedAt
    ) {
        this(userId, programId, title, description, goalId, status, source, startDate, schedule,
            phaseOrder, phases, createdAt, updatedAt, completedAt, null);
    }
}
