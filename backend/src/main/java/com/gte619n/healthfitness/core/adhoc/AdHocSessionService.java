package com.gte619n.healthfitness.core.adhoc;

import com.gte619n.healthfitness.core.workout.Workout;
import com.gte619n.healthfitness.core.workout.WorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.InvalidSessionLogException;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSessionCompletionService.LoggedPrescription;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Ad-hoc run lifecycle (IMPL-ADHOC-01 Phase 3 / AD-05, AD-07). A run has no
 * server-side "start": the terminal {@link #complete} materializes the run's
 * snapshot from the template the first time its client-minted {@code sessionId}
 * arrives and records the outcome, idempotently under outbox replay.
 *
 * <p>On COMPLETED it fans out a flat {@link Workout} (source {@code "adhoc"},
 * id {@code "{adhocId}_{sessionId}"} so a re-PUT upserts), refreshes the
 * template's {@code runCount}/{@code lastPerformedAt} from the persisted runs
 * (derived, never blind-incremented → replay-safe), and recomputes the ISO
 * week's aggregate across programs + ad-hoc via
 * {@link WorkoutSessionCompletionService#recomputeWeekFor} so streaks/volume
 * count the run (D6). The snapshot + validation mirror the program completion
 * service (AD-07).
 */
@Service
public class AdHocSessionService {

    private final AdHocWorkoutRepository workouts;
    private final AdHocSessionRepository sessions;
    private final WorkoutRepository flatWorkouts;
    private final WorkoutSessionCompletionService completion;

    public AdHocSessionService(
        AdHocWorkoutRepository workouts,
        AdHocSessionRepository sessions,
        WorkoutRepository flatWorkouts,
        WorkoutSessionCompletionService completion
    ) {
        this.workouts = workouts;
        this.sessions = sessions;
        this.flatWorkouts = flatWorkouts;
        this.completion = completion;
    }

    /**
     * Upsert a run's outcome. Materializes the snapshot from the template on
     * first arrival.
     *
     * @throws IllegalArgumentException when the template doesn't exist
     * @throws InvalidSessionLogException when the request is invalid
     */
    public ScheduledWorkout complete(
        String userId, String adhocId, String sessionId,
        ScheduledStatus status, LocalDate date, Instant completedAt,
        Integer durationSeconds, List<LoggedPrescription> logged, Integer feeling
    ) {
        AdHocWorkout template = workouts.findById(userId, adhocId)
            .orElseThrow(() -> new IllegalArgumentException("Ad-hoc workout not found: " + adhocId));

        WorkoutDay templateDay = template.day() != null
            ? template.day()
            : new WorkoutDay("wd_adhoc", template.title(), null, null, 0, List.of());
        List<LoggedPrescription> entries = logged == null ? List.of() : logged;

        List<String> issues = validate(templateDay, status, completedAt, durationSeconds, feeling, entries);
        if (!issues.isEmpty()) {
            throw new InvalidSessionLogException(issues);
        }

        boolean completed = status == ScheduledStatus.COMPLETED;
        Map<String, List<LoggedSet>> setsByKey = new HashMap<>();
        Map<String, String> substituteByKey = new HashMap<>();
        if (completed) {
            for (LoggedPrescription e : entries) {
                setsByKey.put(key(e.blockId(), e.orderIndex()), e.sets() == null ? List.of() : e.sets());
                if (e.exerciseId() != null && !e.exerciseId().isBlank()) {
                    substituteByKey.put(key(e.blockId(), e.orderIndex()), e.exerciseId());
                }
            }
        }

        LocalDate runDate = date != null ? date
            : (completedAt != null ? completedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate() : LocalDate.now());
        ScheduledWorkout run = new ScheduledWorkout(
            userId, adhocId, sessionId, runDate,
            null, templateDay.dayId(),
            templateDay.label() != null ? templateDay.label() : template.title(),
            1, false, templateDay.locationId(), status,
            withLoggedSets(templateDay, setsByKey, substituteByKey),
            completed ? completedAt : null,
            completed ? durationSeconds : null,
            completed ? feeling : null
        );
        sessions.save(adhocId, run);

        String workoutId = adhocId + "_" + sessionId;
        if (completed) {
            flatWorkouts.save(new Workout(
                userId, workoutId, "STRENGTH", run.locationId(),
                completedAt.minusSeconds(durationSeconds), completedAt, "adhoc", null, null));
        } else {
            flatWorkouts.delete(userId, workoutId);
        }

        refreshRunCounters(userId, adhocId, template);
        // Unified weekly recompute (programs + ad-hoc) + workout metric republish.
        completion.recomputeWeekFor(userId, runDate);
        return run;
    }

    public java.util.Optional<ScheduledWorkout> findSession(String userId, String adhocId, String sessionId) {
        return sessions.findById(userId, adhocId, sessionId);
    }

    /**
     * Recompute {@code runCount}/{@code lastPerformedAt} from the persisted runs
     * (idempotent: a re-PUT of the same sessionId leaves the count unchanged
     * because it's derived from the row set, not incremented).
     */
    private void refreshRunCounters(String userId, String adhocId, AdHocWorkout template) {
        List<ScheduledWorkout> runs = sessions.findByWorkout(userId, adhocId);
        int completedCount = 0;
        LocalDate last = null;
        for (ScheduledWorkout r : runs) {
            if (r.status() == ScheduledStatus.COMPLETED) {
                completedCount++;
                if (r.date() != null && (last == null || r.date().isAfter(last))) {
                    last = r.date();
                }
            }
        }
        Instant lastPerformedAt = last == null ? null : last.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        AdHocWorkout updated = new AdHocWorkout(
            template.userId(), template.adhocId(), template.title(), template.summary(),
            template.source(), template.prompt(), template.equipmentContext(),
            template.targetDurationMinutes(), template.estimatedDurationSeconds(),
            template.tags(), template.pinned(), template.day(),
            completedCount, lastPerformedAt, template.createdAt(), Instant.now());
        workouts.save(updated);
    }

    // ---- validation (mirrors WorkoutSessionCompletionService, AD-07) ----

    private static List<String> validate(
        WorkoutDay day, ScheduledStatus status, Instant completedAt,
        Integer durationSeconds, Integer feeling, List<LoggedPrescription> logged
    ) {
        List<String> issues = new ArrayList<>();
        if (status == null || status == ScheduledStatus.PLANNED) {
            issues.add("Status must be COMPLETED or SKIPPED.");
            return issues;
        }
        if (feeling != null && (feeling < 1 || feeling > 5)) {
            issues.add("feeling must be between 1 and 5.");
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
            issues.add("A " + status + " session clears actuals; logged sets are not allowed.");
        }
        Set<String> known = prescriptionKeys(day);
        Set<String> seen = new HashSet<>();
        for (LoggedPrescription e : logged) {
            String where = "block '" + e.blockId() + "' / prescription " + e.orderIndex();
            if (!known.contains(key(e.blockId(), e.orderIndex()))) {
                issues.add("No prescription at " + where + ".");
            }
            if (!seen.add(key(e.blockId(), e.orderIndex()))) {
                issues.add("Duplicate logged entry for " + where + ".");
            }
            validateSets(issues, where, e.sets());
        }
        return issues;
    }

    private static void validateSets(List<String> issues, String where, List<LoggedSet> sets) {
        if (sets == null) return;
        for (int i = 0; i < sets.size(); i++) {
            LoggedSet set = sets.get(i);
            if (set == null) continue;
            String at = " at " + where + ", set " + i + ".";
            if (set.weightLbs() != null && set.weightLbs() < 0) issues.add("weightLbs must not be negative" + at);
            if (set.reps() != null && set.reps() < 0) issues.add("reps must not be negative" + at);
            if (set.rpe() != null && (set.rpe() < 0 || set.rpe() > 10)) issues.add("rpe must be between 0 and 10" + at);
            if (set.restSeconds() != null && set.restSeconds() < 0) issues.add("restSeconds must not be negative" + at);
            if (set.durationSeconds() != null && set.durationSeconds() < 0) {
                issues.add("durationSeconds must not be negative" + at);
            }
        }
    }

    private static Set<String> prescriptionKeys(WorkoutDay day) {
        Set<String> keys = new HashSet<>();
        if (day == null || day.blocks() == null) return keys;
        for (Block b : day.blocks()) {
            if (b.prescriptions() == null) continue;
            for (Prescription rx : b.prescriptions()) {
                keys.add(key(b.blockId(), rx.orderIndex()));
            }
        }
        return keys;
    }

    private static WorkoutDay withLoggedSets(
        WorkoutDay day, Map<String, List<LoggedSet>> setsByKey, Map<String, String> substituteByKey
    ) {
        if (day == null || day.blocks() == null) return day;
        List<Block> blocks = new ArrayList<>();
        for (Block b : day.blocks()) {
            List<Prescription> rxs = new ArrayList<>();
            if (b.prescriptions() != null) {
                for (Prescription rx : b.prescriptions()) {
                    String k = key(b.blockId(), rx.orderIndex());
                    List<LoggedSet> sets = setsByKey.get(k);
                    String exerciseId = substituteByKey.getOrDefault(k, rx.exerciseId());
                    rxs.add(new Prescription(
                        exerciseId, rx.orderIndex(), rx.sets(), rx.repsMin(), rx.repsMax(),
                        rx.durationSeconds(), rx.intensity(), rx.restSeconds(), rx.tempo(),
                        rx.notes(), rx.deloadModifier(),
                        sets == null ? null : List.copyOf(sets)));
                }
            }
            blocks.add(new Block(b.blockId(), b.type(), b.title(), b.orderIndex(), rxs));
        }
        return new WorkoutDay(day.dayId(), day.label(), day.dayOfWeek(),
            day.locationId(), day.orderIndex(), blocks);
    }

    private static String key(String blockId, int orderIndex) {
        return blockId + "#" + orderIndex;
    }
}
