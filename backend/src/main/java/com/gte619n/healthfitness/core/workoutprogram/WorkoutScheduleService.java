package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.exercise.BlockType;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
                        // IMPL-DELOAD-01 (DD-5): deload-week snapshots start with
                        // reduced sets so the week is lighter even before the
                        // engine has stamped any target. Load stays engine-owned.
                        ScheduledStatus.PLANNED, isDeload ? withDeloadSets(day) : day,
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

    /** Working-set block types whose set counts a deload week reduces (mirrors the engine's D21 set). */
    private static final Set<BlockType> DELOAD_ELIGIBLE_BLOCKS =
        EnumSet.of(BlockType.MAIN, BlockType.ACCESSORY, BlockType.CORE);
    private static final double DELOAD_SETS_MULTIPLIER = 0.5;

    /**
     * IMPL-DELOAD-01 (D1/DD-5): a deload-week day snapshot carries reduced set
     * counts — per-prescription {@link DeloadModifier#setsMultiplier()} when the
     * program author set one, else × 0.5 — clamped to a minimum of one set.
     * Warm-up/timed/other block types and prescriptions without a set count are
     * left untouched.
     */
    private static WorkoutDay withDeloadSets(WorkoutDay day) {
        if (day.blocks() == null) return day;
        List<Block> blocks = new ArrayList<>();
        for (Block b : day.blocks()) {
            if (b.type() == null || !DELOAD_ELIGIBLE_BLOCKS.contains(b.type()) || b.prescriptions() == null) {
                blocks.add(b);
                continue;
            }
            List<Prescription> rxs = new ArrayList<>();
            for (Prescription rx : b.prescriptions()) {
                if (rx.sets() == null) {
                    rxs.add(rx);
                    continue;
                }
                double mult = rx.deloadModifier() != null && rx.deloadModifier().setsMultiplier() != null
                    ? rx.deloadModifier().setsMultiplier() : DELOAD_SETS_MULTIPLIER;
                int sets = Math.max(1, (int) Math.round(rx.sets() * mult));
                rxs.add(new Prescription(
                    rx.exerciseId(), rx.orderIndex(), sets, rx.repsMin(), rx.repsMax(),
                    rx.durationSeconds(), rx.intensity(), rx.restSeconds(), rx.tempo(),
                    rx.notes(), rx.deloadModifier(), rx.loggedSets(),
                    rx.targetWeightLbs(), rx.loadBasis(), rx.rationale()));
            }
            blocks.add(new Block(b.blockId(), b.type(), b.title(), b.orderIndex(), rxs));
        }
        return new WorkoutDay(day.dayId(), day.label(), day.dayOfWeek(), day.locationId(),
            day.orderIndex(), blocks);
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
