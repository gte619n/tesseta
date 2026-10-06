package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.exercise.BlockType;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
        // Resume each exercise's last progressed prescription — weight AND the rep
        // band/set count it was prescribed (or demonstrated) at, since a load is
        // only meaningful at its reps — so a re-run picks up where training left
        // off instead of resetting to the author's week-one template. Deload
        // history never contributes volume (latestCompletedPrescriptions strips
        // it), so a redo can't inherit a deload week's compressed sets/reps.
        // Carrying the rationale is also what keeps the coach's rep-target lead
        // intact — without it the logger falls back to the stale last-session rep
        // carry (the "announces 15, snaps to 8" bug).
        Map<String, Prescription> resumeByExercise = latestCompletedPrescriptions(userId, programId);
        WorkoutDay resumed = resumeDay(day, resumeByExercise);
        ScheduledWorkout session = new ScheduledWorkout(
            userId, programId, scheduledId,
            date, phaseId, dayId, day.label(),
            1, false, day.locationId(),
            ScheduledStatus.PLANNED, resumed,
            null, null, null
        );
        scheduled.save(session);
        return session;
    }

    /**
     * Continue a finished (or still-active) program in place: append more dated
     * sessions after its last one and flip it back to ACTIVE. Unlike
     * {@link #activate}, which re-lays the author's starting template, every
     * appended prescription resumes from the user's <em>last logged</em>
     * weight/reps/rationale for that exercise — so a continued block picks up
     * exactly where progression left off instead of resetting to week-one loads.
     *
     * <p>{@code scope == WEEK} appends one week of the last phase's microcycle;
     * {@code scope == CYCLE} repeats the whole periodization once more (each
     * phase's weeks, honoring its deload week). Appended weeks start on the
     * Monday after the last scheduled session (never in the past), and existing
     * sessions on a given date+day are left untouched (idempotent re-runs).
     *
     * @return the newly appended sessions
     * @throws IllegalArgumentException when the program is unknown
     * @throws IllegalStateException when the program has no phases to continue
     */
    public List<ScheduledWorkout> continueProgram(
        String userId, String programId, ContinuationScope scope
    ) {
        WorkoutProgram program = programs.findById(userId, programId)
            .orElseThrow(() -> new IllegalArgumentException("Program not found: " + programId));
        List<ProgramPhase> phases = program.phases();
        if (phases == null || phases.isEmpty()) {
            throw new IllegalStateException("Program has no phases to continue: " + programId);
        }

        LocalDate today = LocalDate.now();
        // Anchor off the last COMPLETED session, not the last scheduled one, so a
        // replay is idempotent: the appended sessions carry deterministic
        // "{date}_{dayId}" ids and are skipped below when they already exist, and
        // anchoring off completed work means a blindly re-delivered request lands
        // on the SAME dates rather than stacking another week past the first
        // append (write-contract DETERMINISTIC_ID).
        LocalDate lastCompleted = scheduled
            .latestDateByStatus(userId, programId, ScheduledStatus.COMPLETED)
            .orElse(today);
        // Start the Monday after the last completed week, but never before this
        // week — a program finished weeks ago should resume now, not back-fill.
        LocalDate startMonday = lastCompleted
            .with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY))
            .plusWeeks(1);
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        if (startMonday.isBefore(thisMonday)) {
            startMonday = thisMonday;
        }
        // Mid-week continuation: if the block would start this week but we're past
        // Monday, some day slots are already behind us — push it to next Monday so
        // the whole continued week lands ahead and nothing is dated in the past.
        if (startMonday.isEqual(thisMonday) && today.isAfter(thisMonday)) {
            startMonday = thisMonday.plusWeeks(1);
        }

        Map<String, Prescription> resumeByExercise = latestCompletedPrescriptions(userId, programId);

        // The (phase, isDeload) weeks to lay out, in order.
        List<PhaseWeek> weeks = new ArrayList<>();
        if (scope == ContinuationScope.CYCLE) {
            for (ProgramPhase phase : phases) {
                int count = Math.max(1, phase.weeks());
                for (int week = 1; week <= count; week++) {
                    boolean isDeload = phase.deloadWeekIndex() != null && phase.deloadWeekIndex() == week;
                    weeks.add(new PhaseWeek(phase, week, isDeload));
                }
            }
        } else {
            // One more normal training week of the last phase.
            ProgramPhase last = phases.get(phases.size() - 1);
            weeks.add(new PhaseWeek(last, Math.max(1, last.weeks()) + 1, false));
        }

        List<ScheduledWorkout> sessions = new ArrayList<>();
        LocalDate weekMonday = startMonday;
        for (PhaseWeek pw : weeks) {
            for (WorkoutDay day : pw.phase.days()) {
                LocalDate date = weekMonday.plusDays(day.dayOfWeek().ordinal());
                String scheduledId = date + "_" + day.dayId();
                if (scheduled.findById(userId, programId, scheduledId).isPresent()) {
                    continue; // never rewrite an existing session
                }
                WorkoutDay resumed = resumeDay(day, resumeByExercise);
                WorkoutDay snapshot = pw.isDeload ? withDeloadSets(resumed) : resumed;
                sessions.add(new ScheduledWorkout(
                    userId, programId, scheduledId,
                    date, pw.phase.phaseId(), day.dayId(), day.label(),
                    pw.weekIndex, pw.isDeload, day.locationId(),
                    ScheduledStatus.PLANNED, snapshot,
                    null, null, null
                ));
            }
            weekMonday = weekMonday.plusWeeks(1);
        }
        scheduled.saveAll(sessions);
        // setStatus (not update) intentionally bypasses the sticky-COMPLETED
        // guard so a finished program can resume training.
        programService.setStatus(userId, programId, ProgramStatus.ACTIVE);
        return sessions;
    }

    /** One week to lay out during a continuation: which phase, its 1-based index, deload flag. */
    private record PhaseWeek(ProgramPhase phase, int weekIndex, boolean isDeload) {}

    /**
     * The most recent <em>working</em> (non-deload) COMPLETED prescription per
     * exercise across the program — the load/reps/rationale the engine had settled
     * on by the last real training session. Used to seed a redo ({@link
     * #materializeOne}) or a continuation ({@link #continueProgram}) so it resumes
     * from real numbers, not the author's starting template.
     *
     * <p>Deload weeks are skipped: their loads are artificially suppressed
     * (observe-only — the engine never corrects its belief on a deload, so a
     * deload session doesn't represent progress). Seeding a "start today" or a
     * continued block from a deload would hand the athlete a reduced weight at full
     * volume. A deload prescription is used only as a last-resort fallback for an
     * exercise that has no non-deload history at all.
     */
    private Map<String, Prescription> latestCompletedPrescriptions(String userId, String programId) {
        Map<String, Prescription> working = new HashMap<>();        // newest working rx (volume, maybe load)
        Map<String, Prescription> workingLoad = new HashMap<>();    // newest working rx that CARRIES a target
        Map<String, Demonstrated> demonstrated = new HashMap<>();   // newest top set actually lifted (working weeks)
        Map<String, Prescription> deloadOnly = new HashMap<>();     // fallback when that's all there is
        // findByStatus returns newest scheduled-date first, so the first time we
        // see an exercise is its most recent entry (in each bucket).
        for (ScheduledWorkout sw : scheduled.findByStatus(userId, programId, ScheduledStatus.COMPLETED)) {
            WorkoutDay snapshot = sw.session();
            if (snapshot == null || snapshot.blocks() == null) {
                continue;
            }
            for (Block b : snapshot.blocks()) {
                if (b.prescriptions() == null) {
                    continue;
                }
                for (Prescription rx : b.prescriptions()) {
                    if (rx.exerciseId() == null) {
                        continue;
                    }
                    if (sw.isDeload()) {
                        // Deload loads are artificially suppressed (observe-only), so
                        // neither their prescriptions nor their logged sets represent
                        // the trajectory — keep them only as a last-resort fallback.
                        deloadOnly.putIfAbsent(rx.exerciseId(), rx);
                        continue;
                    }
                    working.putIfAbsent(rx.exerciseId(), rx);
                    if (rx.targetWeightLbs() != null) {
                        workingLoad.putIfAbsent(rx.exerciseId(), rx);
                    }
                    Demonstrated top = Demonstrated.topOf(rx, sw.date());
                    if (top != null) {
                        demonstrated.putIfAbsent(rx.exerciseId(), top);
                    }
                }
            }
        }
        Map<String, Prescription> resume = new HashMap<>();
        Set<String> ids = new HashSet<>(working.keySet());
        ids.addAll(deloadOnly.keySet());
        for (String id : ids) {
            Prescription vol = working.get(id);
            if (vol == null) {
                // Deload-only history: last resort, and only its LOAD counts — a
                // deload's compressed volume must never seed a working session.
                resume.put(id, loadOnly(deloadOnly.get(id)));
                continue;
            }
            resume.put(id, mergeResume(vol, workingLoad.get(id), demonstrated.get(id), deloadOnly.get(id)));
        }
        return resume;
    }

    /** A deload rx reduced to its load fields: volume (sets/reps) never travels. */
    private static Prescription loadOnly(Prescription rx) {
        return new Prescription(
            rx.exerciseId(), rx.orderIndex(), null, null, null,
            rx.durationSeconds(), rx.intensity(), rx.restSeconds(), rx.tempo(),
            rx.notes(), rx.deloadModifier(), null,
            rx.targetWeightLbs(), rx.loadBasis(), rx.rationale());
    }

    /** The heaviest set the athlete actually completed for an exercise in one session. */
    private record Demonstrated(double weightLbs, int reps, LocalDate date) {
        static Demonstrated topOf(Prescription rx, LocalDate date) {
            if (rx.loggedSets() == null) return null;
            Demonstrated top = null;
            for (LoggedSet set : rx.loggedSets()) {
                if (set == null || set.weightLbs() == null || set.weightLbs() <= 0) continue;
                if (set.reps() == null || set.reps() <= 0) continue;
                if (top == null || set.weightLbs() > top.weightLbs()) {
                    top = new Demonstrated(set.weightLbs(), set.reps(), date);
                }
            }
            return top;
        }
    }

    /**
     * The resume prescription for one exercise: volume from the newest working
     * session, load from whichever is stronger evidence of where training left
     * off. The stored pre-session target is NOT trusted on its own — when the
     * athlete demonstrably lifted MORE than the newest stored target (they
     * overrode the plan, or the stored rx is a stale template copy the engine
     * never re-stamped), the demonstrated top set wins and the rep band widens
     * to include the demonstrated reps. Without this a continuation can
     * resurrect a week-one template weight the athlete outgrew weeks ago (the
     * "did 180×8, got told 160×12–15" bug): the engine's derived next
     * prescription is only stamped onto already-materialized future sessions,
     * so a completion with nothing planned ahead silently drops the trajectory,
     * and the athlete's actual performance is the only durable record of it.
     */
    private static Prescription mergeResume(
        Prescription vol, Prescription loadRx, Demonstrated demo, Prescription deloadRx) {
        // Stored-load candidate: newest working rx with a target; else the deload
        // fallback's (no longer shadowed by a newer null-target working rx).
        Prescription stored = loadRx != null ? loadRx
            : (deloadRx != null && deloadRx.targetWeightLbs() != null ? deloadRx : null);
        boolean demoWins = demo != null
            && (stored == null || demo.weightLbs() > stored.targetWeightLbs());
        if (!demoWins) {
            if (stored == null || stored == vol) {
                return vol; // nothing better than the newest working rx itself
            }
            if (loadRx != null) {
                // A weight is only meaningful at the reps it was prescribed for:
                // the stored working rx's band and set count travel WITH its load
                // (an engine 170×8–9 must not come back as 170×12–15).
                return new Prescription(
                    vol.exerciseId(), vol.orderIndex(),
                    loadRx.sets() != null ? loadRx.sets() : vol.sets(),
                    loadRx.repsMin() != null ? loadRx.repsMin() : vol.repsMin(),
                    loadRx.repsMax() != null ? loadRx.repsMax() : vol.repsMax(),
                    vol.durationSeconds(), vol.intensity(), vol.restSeconds(), vol.tempo(),
                    vol.notes(), vol.deloadModifier(), null,
                    loadRx.targetWeightLbs(), loadRx.loadBasis(), loadRx.rationale());
            }
            // Deload last resort: its load rides on the newest WORKING volume —
            // a deload's compressed sets/reps never count.
            return new Prescription(
                vol.exerciseId(), vol.orderIndex(), vol.sets(), vol.repsMin(), vol.repsMax(),
                vol.durationSeconds(), vol.intensity(), vol.restSeconds(), vol.tempo(),
                vol.notes(), vol.deloadModifier(), null,
                stored.targetWeightLbs(), stored.loadBasis(), stored.rationale());
        }
        // Demonstrated performance wins: resume its weight, and make sure the rep
        // band admits the reps it was actually lifted for (8 reps at 180 must not
        // come back as a 12–15 band that reads as failure before the first set).
        Integer repsMin = vol.repsMin() == null ? null : Math.min(vol.repsMin(), demo.reps());
        Integer repsMax = vol.repsMax() == null ? null : Math.max(vol.repsMax(), demo.reps());
        String basis = "Resumes your last logged " + fmtLbs(demo.weightLbs())
            + " lb × " + demo.reps() + " (" + demo.date() + ").";
        return new Prescription(
            vol.exerciseId(), vol.orderIndex(), vol.sets(), repsMin, repsMax,
            vol.durationSeconds(), vol.intensity(), vol.restSeconds(), vol.tempo(),
            vol.notes(), vol.deloadModifier(), null,
            demo.weightLbs(), basis, null);
    }

    private static String fmtLbs(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /**
     * Rebuild a day's prescriptions so each one resumes from the user's last
     * working prescription for that exercise (weight TOGETHER with the rep band
     * and set count it belongs to, load-basis, and the engine rationale), while
     * keeping the template's structure (order, rest, tempo, intensity, deload
     * modifier). Used by both {@link #continueProgram} and {@link #materializeOne}
     * — the resume map already guarantees deload history contributes at most a
     * load, never volume. Logged sets are deliberately dropped — the new session
     * is PLANNED, not performed. Exercises with no history (or a resume entry
     * with null fields) keep the template values via the per-field null-guards.
     */
    private static WorkoutDay resumeDay(WorkoutDay day, Map<String, Prescription> resumeByExercise) {
        if (day.blocks() == null || resumeByExercise.isEmpty()) {
            return day;
        }
        List<Block> blocks = new ArrayList<>();
        for (Block b : day.blocks()) {
            if (b.prescriptions() == null) {
                blocks.add(b);
                continue;
            }
            List<Prescription> rxs = new ArrayList<>();
            for (Prescription rx : b.prescriptions()) {
                Prescription prev = resumeByExercise.get(rx.exerciseId());
                if (prev == null) {
                    rxs.add(rx);
                    continue;
                }
                rxs.add(new Prescription(
                    rx.exerciseId(), rx.orderIndex(),
                    prev.sets() != null ? prev.sets() : rx.sets(),
                    prev.repsMin() != null ? prev.repsMin() : rx.repsMin(),
                    prev.repsMax() != null ? prev.repsMax() : rx.repsMax(),
                    rx.durationSeconds(), rx.intensity(), rx.restSeconds(), rx.tempo(),
                    rx.notes(), rx.deloadModifier(),
                    null, // PLANNED session — no logged sets carried forward
                    prev.targetWeightLbs(), prev.loadBasis(), prev.rationale()));
            }
            blocks.add(new Block(b.blockId(), b.type(), b.title(), b.orderIndex(), rxs));
        }
        return new WorkoutDay(day.dayId(), day.label(), day.dayOfWeek(), day.locationId(),
            day.orderIndex(), blocks);
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

    /**
     * Lazy auto-continuation — never leave the athlete at a dead end. If an
     * eligible program has no upcoming PLANNED session on or after {@code today},
     * append the next full cycle in place ({@link #continueProgram} with CYCLE,
     * resuming from the last working loads) so there is always a next workout
     * queued. A program with future work, or an ARCHIVED one (deliberately shelved),
     * is left untouched. Idempotent: {@code continueProgram} uses deterministic
     * session ids and anchors off the last completed week, so calling this on every
     * workouts-screen open never stacks duplicate weeks.
     *
     * @return the program's upcoming PLANNED sessions after any continuation,
     *         soonest first (empty only for an ARCHIVED/empty program)
     */
    public List<ScheduledWorkout> ensureUpcoming(String userId, String programId, LocalDate today) {
        WorkoutProgram program = programs.findById(userId, programId)
            .orElseThrow(() -> new IllegalArgumentException("Program not found: " + programId));
        List<ScheduledWorkout> upcoming = upcomingPlanned(userId, programId, today);
        if (!upcoming.isEmpty() || program.status() == ProgramStatus.ARCHIVED) {
            return upcoming;
        }
        // Out of scheduled work on a program the athlete is still following —
        // extend it one more cycle and return the freshly appended upcoming set.
        continueProgram(userId, programId, ContinuationScope.CYCLE);
        return upcomingPlanned(userId, programId, today);
    }

    /** Upcoming PLANNED sessions on or after {@code today}, soonest first. */
    private List<ScheduledWorkout> upcomingPlanned(String userId, String programId, LocalDate today) {
        List<ScheduledWorkout> out = new ArrayList<>();
        for (ScheduledWorkout sw : scheduled.findByProgram(userId, programId, today, LocalDate.MAX)) {
            if (sw.status() == ScheduledStatus.PLANNED && sw.date() != null && !sw.date().isBefore(today)) {
                out.add(sw);
            }
        }
        out.sort(Comparator.comparing(
            ScheduledWorkout::date, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
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
