package com.gte619n.healthfitness.core.workoutprogram;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Materializes a program's phases into dated {@link ScheduledWorkout}s. Each
 * phase's weekly microcycle is laid across its weeks from the phase's target
 * start; sessions in the deload week are flagged. Re-activating clears future
 * PLANNED sessions and rewrites them, never touching past/COMPLETED ones.
 */
@Service
public class WorkoutScheduleService {

    private final WorkoutProgramRepository programs;
    private final ScheduledWorkoutRepository scheduled;
    private final WorkoutProgramService programService;

    public WorkoutScheduleService(
        WorkoutProgramRepository programs,
        ScheduledWorkoutRepository scheduled,
        WorkoutProgramService programService
    ) {
        this.programs = programs;
        this.scheduled = scheduled;
        this.programService = programService;
    }

    /** Activate a program: materialize its sessions and mark it ACTIVE. */
    public List<ScheduledWorkout> activate(String userId, String programId) {
        WorkoutProgram program = programs.findById(userId, programId)
            .orElseThrow(() -> new IllegalArgumentException("Program not found: " + programId));

        LocalDate from = program.startDate() != null ? program.startDate() : LocalDate.now();
        LocalDate today = LocalDate.now();
        LocalDate clearFrom = from.isAfter(today) ? from : today;
        scheduled.deletePlannedFrom(userId, programId, clearFrom);

        List<ScheduledWorkout> sessions = new ArrayList<>();
        for (ProgramPhase phase : program.phases()) {
            int weeks = Math.max(1, phase.weeks());
            LocalDate phaseStart = phase.targetStartDate() != null ? phase.targetStartDate() : from;
            LocalDate weekOneMonday = phaseStart.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
            for (int week = 1; week <= weeks; week++) {
                LocalDate weekMonday = weekOneMonday.plusWeeks(week - 1L);
                boolean isDeload = phase.deloadWeekIndex() != null && phase.deloadWeekIndex() == week;
                for (WorkoutDay day : phase.days()) {
                    LocalDate date = weekMonday.plusDays(day.dayOfWeek().ordinal());
                    if (date.isBefore(clearFrom)) {
                        continue; // don't rewrite past sessions
                    }
                    sessions.add(new ScheduledWorkout(
                        userId, programId,
                        date + "_" + day.dayId(),
                        date, phase.phaseId(), day.dayId(), day.label(),
                        week, isDeload, day.locationId(),
                        ScheduledStatus.PLANNED, day,
                        null, null
                    ));
                }
            }
        }
        scheduled.saveAll(sessions);
        programService.setStatus(userId, programId, ProgramStatus.ACTIVE);
        return scheduled.findByProgram(userId, programId, clearFrom, clearFrom.plusYears(1));
    }

    public List<ScheduledWorkout> calendar(String userId, String programId, LocalDate from, LocalDate to) {
        return scheduled.findByProgram(userId, programId, from, to);
    }

    /** Number of COMPLETED sessions in a program (no document reads on Firestore). */
    public int completedCount(String userId, String programId) {
        return scheduled.countByStatus(userId, programId, ScheduledStatus.COMPLETED);
    }

    /** Date of the most recent COMPLETED session in a program, if any. */
    public Optional<LocalDate> lastCompletedDate(String userId, String programId) {
        return scheduled.latestDateByStatus(userId, programId, ScheduledStatus.COMPLETED);
    }
}
