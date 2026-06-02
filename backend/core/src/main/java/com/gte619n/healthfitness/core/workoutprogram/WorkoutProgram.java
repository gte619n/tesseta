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
    Instant completedAt
) {}
