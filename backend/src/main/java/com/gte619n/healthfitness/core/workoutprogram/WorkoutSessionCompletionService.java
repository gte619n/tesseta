package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.core.goals.eval.MetricKey;
import com.gte619n.healthfitness.core.goals.events.MetricChangedPublisher;
import com.gte619n.healthfitness.core.workout.Workout;
import com.gte619n.healthfitness.core.workout.WorkoutRepository;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregate;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregateRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Applies the ADR-0012 completion upsert: a session's outcome — COMPLETED or
 * SKIPPED, with full per-set actuals — lands on the existing
 * {@link ScheduledWorkout}, the same record the history import writes and the
 * Workout History view reads. Transitions are permissive (any of
 * PLANNED/COMPLETED/SKIPPED → COMPLETED or SKIPPED) because after-the-fact
 * editing is the correction lever; a repeat upsert replaces actuals and re-runs
 * the fan-out, so outbox retries are safe.
 *
 * <p>Fan-out on COMPLETED (server-side, never the client): one session-level
 * {@link Workout} record (id {@code "{programId}_{scheduledId}"}, so re-PUTs
 * upsert), a recompute of that ISO week's {@link WeeklyWorkoutAggregate}
 * across <em>all</em> the user's programs, and a {@code MetricChangedEvent}
 * per workout metric key — published AFTER the saves, never before, mirroring
 * NutritionService. SKIPPED clears actuals, removes a previously fanned-out
 * Workout, and recomputes the same week.
 */
@Service
public class WorkoutSessionCompletionService {

    private static final List<MetricKey> WORKOUT_KEYS = List.of(
        MetricKey.WORKOUTS_COUNT,
        MetricKey.WORKOUTS_WEEKLY_VOLUME
    );

    /**
     * Actuals for one prescription. Prescriptions have no id, so entries key
     * by {@code (blockId, orderIndex)} against the session snapshot.
     */
    public record LoggedPrescription(String blockId, int orderIndex, List<LoggedSet> sets) {}

    /**
     * Rejected upsert. Carries the flat human-readable issue list (same shape
     * as {@link WorkoutProgramValidator}) so the API can flag fields inline
     * rather than silently dropping anything.
     */
    public static class InvalidSessionLogException extends RuntimeException {
        private final List<String> issues;

        public InvalidSessionLogException(List<String> issues) {
            super(String.join("; ", issues));
            this.issues = List.copyOf(issues);
        }

        public List<String> issues() {
            return issues;
        }
    }

    private final ScheduledWorkoutRepository scheduled;
    private final WorkoutProgramRepository programs;
    private final WorkoutRepository workouts;
    private final WeeklyWorkoutAggregateRepository aggregates;
    private final MetricChangedPublisher metricChangedPublisher;

    public WorkoutSessionCompletionService(
        ScheduledWorkoutRepository scheduled,
        WorkoutProgramRepository programs,
        WorkoutRepository workouts,
        WeeklyWorkoutAggregateRepository aggregates,
        MetricChangedPublisher metricChangedPublisher
    ) {
        this.scheduled = scheduled;
        this.programs = programs;
        this.workouts = workouts;
        this.aggregates = aggregates;
        this.metricChangedPublisher = metricChangedPublisher;
    }

    /**
     * Upsert the outcome of one scheduled session and run the fan-out.
     * Idempotent: replaying the same request converges on the same state.
     *
     * @throws IllegalArgumentException when the session doesn't exist
     * @throws InvalidSessionLogException when the request is invalid
     *         (bad status, missing COMPLETED fields, unknown logged keys)
     */
    public ScheduledWorkout complete(
        String userId,
        String programId,
        String scheduledId,
        ScheduledStatus status,
        Instant completedAt,
        Integer durationSeconds,
        List<LoggedPrescription> logged
    ) {
        ScheduledWorkout sw = scheduled.findById(userId, programId, scheduledId)
            .orElseThrow(() -> new IllegalArgumentException("Scheduled workout not found: " + scheduledId));

        List<LoggedPrescription> entries = logged == null ? List.of() : logged;
        List<String> issues = validate(sw, status, completedAt, durationSeconds, entries);
        if (!issues.isEmpty()) {
            throw new InvalidSessionLogException(issues);
        }

        // The request's actuals fully replace the previous ones: prescriptions
        // absent from the payload end up with no logged sets (and SKIPPED
        // clears everything), so a repeat PUT is a true upsert.
        boolean completed = status == ScheduledStatus.COMPLETED;
        Map<String, List<LoggedSet>> setsByKey = new HashMap<>();
        if (completed) {
            for (LoggedPrescription e : entries) {
                setsByKey.put(key(e.blockId(), e.orderIndex()), e.sets() == null ? List.of() : e.sets());
            }
        }
        ScheduledWorkout updated = new ScheduledWorkout(
            sw.userId(), sw.programId(), sw.scheduledId(), sw.date(),
            sw.phaseId(), sw.dayId(), sw.dayLabel(), sw.weekIndexInPhase(),
            sw.isDeload(), sw.locationId(), status,
            withLoggedSets(sw.session(), setsByKey),
            completed ? completedAt : null,
            completed ? durationSeconds : null
        );
        scheduled.save(updated);

        if (completed) {
            workouts.save(new Workout(
                userId, workoutId(programId, scheduledId), "STRENGTH",
                locationOf(updated),
                completedAt.minusSeconds(durationSeconds), completedAt,
                "logger", null, null));
        } else {
            // Un-completing removes the previously fanned-out session record.
            workouts.delete(userId, workoutId(programId, scheduledId));
        }
        recomputeWeek(userId, updated.date());
        metricChangedPublisher.publishAll(userId, WORKOUT_KEYS);
        return updated;
    }

    // ---- validation ----

    private static List<String> validate(
        ScheduledWorkout sw,
        ScheduledStatus status,
        Instant completedAt,
        Integer durationSeconds,
        List<LoggedPrescription> logged
    ) {
        List<String> issues = new ArrayList<>();
        if (status != ScheduledStatus.COMPLETED && status != ScheduledStatus.SKIPPED) {
            issues.add("Status must be COMPLETED or SKIPPED.");
            return issues;
        }
        if (status == ScheduledStatus.COMPLETED) {
            if (completedAt == null) {
                issues.add("completedAt is required for a COMPLETED session.");
            }
            if (durationSeconds == null) {
                issues.add("durationSeconds is required for a COMPLETED session.");
            } else if (durationSeconds < 0) {
                issues.add("durationSeconds must not be negative.");
            }
        } else if (!logged.isEmpty()) {
            issues.add("SKIPPED clears actuals; logged sets are not allowed.");
        }
        // Every entry must hit a prescription in the session snapshot — unknown
        // keys are an error (nothing silently dropped, same stance as the
        // IMPL-15 validator).
        Set<String> known = prescriptionKeys(sw.session());
        Set<String> seen = new HashSet<>();
        for (LoggedPrescription e : logged) {
            String where = "block '" + e.blockId() + "' / prescription " + e.orderIndex();
            if (!known.contains(key(e.blockId(), e.orderIndex()))) {
                issues.add("No prescription at " + where + ".");
            }
            if (!seen.add(key(e.blockId(), e.orderIndex()))) {
                issues.add("Duplicate logged entry for " + where + ".");
            }
        }
        return issues;
    }

    private static Set<String> prescriptionKeys(WorkoutDay session) {
        Set<String> keys = new HashSet<>();
        if (session == null || session.blocks() == null) return keys;
        for (Block b : session.blocks()) {
            if (b.prescriptions() == null) continue;
            for (Prescription rx : b.prescriptions()) {
                keys.add(key(b.blockId(), rx.orderIndex()));
            }
        }
        return keys;
    }

    // ---- snapshot rewrite ----

    private static WorkoutDay withLoggedSets(WorkoutDay session, Map<String, List<LoggedSet>> setsByKey) {
        if (session == null || session.blocks() == null) return session;
        List<Block> blocks = new ArrayList<>();
        for (Block b : session.blocks()) {
            List<Prescription> rxs = new ArrayList<>();
            if (b.prescriptions() != null) {
                for (Prescription rx : b.prescriptions()) {
                    List<LoggedSet> sets = setsByKey.get(key(b.blockId(), rx.orderIndex()));
                    rxs.add(new Prescription(
                        rx.exerciseId(), rx.orderIndex(), rx.sets(), rx.repsMin(), rx.repsMax(),
                        rx.durationSeconds(), rx.intensity(), rx.restSeconds(), rx.tempo(),
                        rx.notes(), rx.deloadModifier(),
                        sets == null ? null : List.copyOf(sets)));
                }
            }
            blocks.add(new Block(b.blockId(), b.type(), b.title(), b.orderIndex(), rxs));
        }
        return new WorkoutDay(session.dayId(), session.label(), session.dayOfWeek(),
            session.locationId(), session.orderIndex(), blocks);
    }

    // ---- fan-out ----

    /**
     * Recompute the ISO week's (Monday-start) aggregate from scratch by
     * scanning the user's COMPLETED sessions across all programs in that week.
     * A full recompute (vs. incrementing) is what makes the repeat-PUT and
     * un-complete paths converge.
     */
    private void recomputeWeek(String userId, LocalDate sessionDate) {
        if (sessionDate == null) return;
        LocalDate weekStart = sessionDate.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        int sessionCount = 0;
        double totalTonnage = 0.0;
        for (WorkoutProgram p : programs.findByUser(userId)) {
            for (ScheduledWorkout sw : scheduled.findByProgram(userId, p.programId(), weekStart, weekEnd)) {
                if (sw.status() != ScheduledStatus.COMPLETED) continue;
                sessionCount++;
                totalTonnage += tonnageOf(sw.session());
            }
        }
        aggregates.save(new WeeklyWorkoutAggregate(userId, weekStart, totalTonnage, sessionCount, null, null));
    }

    /**
     * Tonnage = Σ weight×reps over sets where both are present. Weight-only
     * sets (e.g. reps-null imported history) count toward sessionCount via
     * their session but contribute no tonnage (ADR-0012 Decision 5).
     */
    private static double tonnageOf(WorkoutDay session) {
        if (session == null || session.blocks() == null) return 0.0;
        double tonnage = 0.0;
        for (Block b : session.blocks()) {
            if (b.prescriptions() == null) continue;
            for (Prescription rx : b.prescriptions()) {
                if (rx.loggedSets() == null) continue;
                for (LoggedSet set : rx.loggedSets()) {
                    if (set.weightLbs() != null && set.reps() != null) {
                        tonnage += set.weightLbs() * set.reps();
                    }
                }
            }
        }
        return tonnage;
    }

    private static String workoutId(String programId, String scheduledId) {
        return programId + "_" + scheduledId;
    }

    private static String locationOf(ScheduledWorkout sw) {
        if (sw.locationId() != null) return sw.locationId();
        return sw.session() == null ? null : sw.session().locationId();
    }

    private static String key(String blockId, int orderIndex) {
        return blockId + "#" + orderIndex;
    }
}
