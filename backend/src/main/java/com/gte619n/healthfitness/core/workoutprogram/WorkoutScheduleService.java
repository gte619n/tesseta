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
                        null, null, null
                    ));
                }
            }
        }
        scheduled.saveAll(sessions);
        programService.setStatus(userId, programId, ProgramStatus.ACTIVE);
        return scheduled.findByProgram(userId, programId, clearFrom, clearFrom.plusYears(1));
    }

    /**
     * Materialize (or reuse) a single session for one program day on {@code date},
     * independent of the program's scheduled window. This is how a user runs any
     * workout "as today" after the 4-week plan has elapsed or a day was missed:
     * the resulting {@link ScheduledWorkout} is a normal PLANNED row, so it starts,
     * logs, and fans out (Workout, weekly aggregate, metrics) exactly like a
     * scheduled session.
     *
     * <p>Idempotent by the {@code "{date}_{dayId}"} id convention shared with
     * {@link #activate}: running the same day on the same date returns the existing
     * row untouched (a COMPLETED one is not reset — reopen it to review/edit).
     *
     * @throws IllegalArgumentException when the program, phase, or day is unknown
     */
    public ScheduledWorkout materializeOne(
        String userId, String programId, String phaseId, String dayId, LocalDate date
    ) {
        WorkoutProgram program = programs.findById(userId, programId)
            .orElseThrow(() -> new IllegalArgumentException("Program not found: " + programId));
        ProgramPhase phase = program.phases().stream()
            .filter(p -> p.phaseId().equals(phaseId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Phase not found: " + phaseId));
        WorkoutDay day = phase.days().stream()
            .filter(d -> d.dayId().equals(dayId))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Day not found: " + dayId));

        String scheduledId = date + "_" + dayId;
        Optional<ScheduledWorkout> existing = scheduled.findById(userId, programId, scheduledId);
        if (existing.isPresent()) {
            return existing.get();
        }
        ScheduledWorkout session = new ScheduledWorkout(
            userId, programId, scheduledId,
            date, phaseId, dayId, day.label(),
            1, false, day.locationId(),
            ScheduledStatus.PLANNED, day,
            null, null, null
        );
        scheduled.save(session);
        return session;
    }

    public List<ScheduledWorkout> calendar(String userId, String programId, LocalDate from, LocalDate to) {
        return scheduled.findByProgram(userId, programId, from, to);
    }

    /** One scheduled session by id, if it exists. */
    public Optional<ScheduledWorkout> session(String userId, String programId, String scheduledId) {
        return scheduled.findById(userId, programId, scheduledId);
    }

    /** All COMPLETED sessions in a program, newest scheduled-date first (Workout History). */
    public List<ScheduledWorkout> completedSessions(String userId, String programId) {
        return scheduled.findByStatus(userId, programId, ScheduledStatus.COMPLETED);
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
