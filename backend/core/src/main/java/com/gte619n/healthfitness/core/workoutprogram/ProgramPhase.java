package com.gte619n.healthfitness.core.workoutprogram;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A multi-week chapter of a program, with an optional deload week. Phases run
 * in strict sequence (one active at a time). {@code days} is the weekly
 * microcycle repeated each week of the phase.
 */
public record ProgramPhase(
    String phaseId,
    String title,
    String focus,
    int orderIndex,
    ProgramPhaseStatus status,
    int weeks,
    Integer deloadWeekIndex,        // 1-based; null = no deload
    LocalDate targetStartDate,
    LocalDate targetEndDate,
    Instant completedAt,
    List<WorkoutDay> days
) {}
