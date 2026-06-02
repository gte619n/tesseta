package com.gte619n.healthfitness.api.workoutprogram;

import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhase;
import com.gte619n.healthfitness.core.workoutprogram.ProgramPhaseStatus;
import com.gte619n.healthfitness.core.workoutprogram.ProgramSource;
import com.gte619n.healthfitness.core.workoutprogram.ProgramStatus;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Shallow list response — no phase tree. */
public record WorkoutProgramResponse(
    String programId,
    String title,
    String description,
    String goalId,
    ProgramStatus status,
    ProgramSource source,
    LocalDate startDate,
    List<DayOfWeek> trainingDays,
    int totalWeeks,
    int phaseCount,
    int completedPhaseCount,
    Instant createdAt,
    Instant updatedAt
) {
    public static WorkoutProgramResponse from(WorkoutProgram p) {
        List<ProgramPhase> phases = p.phases() == null ? List.of() : p.phases();
        int totalWeeks = phases.stream().mapToInt(ProgramPhase::weeks).sum();
        int completed = (int) phases.stream().filter(ph -> ph.status() == ProgramPhaseStatus.COMPLETED).count();
        return new WorkoutProgramResponse(
            p.programId(), p.title(), p.description(), p.goalId(), p.status(), p.source(),
            p.startDate(),
            p.schedule() == null ? List.of() : p.schedule().trainingDays(),
            totalWeeks, phases.size(), completed,
            p.createdAt(), p.updatedAt()
        );
    }
}
