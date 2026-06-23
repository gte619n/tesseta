package com.gte619n.healthfitness.core.workoutprogram;

import com.gte619n.healthfitness.config.CacheConfig;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Computes the IMPL-18 per-exercise performance digest from the user's
 * {@code COMPLETED} logged sets across every program (including archived /
 * imported history, ADR-0008). Pure and unit-testable: all derivation is a
 * fold over a flat list of {@link PerformedSet}s, and the only Firestore touch
 * is the per-user scan, which is cached for a short TTL keyed by userId.
 *
 * <p>Load grounding (S5/R2): estimated 1RM is Epley {@code w*(1+reps/30)} on the
 * heaviest qualifying set in a trailing window (falling back to all-time if the
 * window is empty). When only weight-only imported rows exist (reps null), the
 * estimate degrades to the top weight with {@code lowConfidence=true} — a
 * conservative floor that informs but never anchors a prescription.
 */
@Service
public class ExercisePerformanceDigestService {

    /** Generous trailing window for the e1RM "recent best" (S5). */
    private static final int E1RM_WINDOW_WEEKS = 26;
    /** Volume-trend buckets: last 4 weeks vs. the 4 weeks before that. */
    private static final int TRAILING_DAYS = 28;
    private static final int PRIOR_DAYS = 56;

    private final WorkoutProgramRepository programs;
    private final ScheduledWorkoutRepository scheduled;

    public ExercisePerformanceDigestService(
        WorkoutProgramRepository programs, ScheduledWorkoutRepository scheduled) {
        this.programs = programs;
        this.scheduled = scheduled;
    }

    /**
     * One {@link ExerciseDigest} per requested exerciseId that has ≥1 completed
     * logged set. Ids with no history are omitted from the returned map.
     */
    public Map<String, ExerciseDigest> digest(String userId, Collection<String> exerciseIds) {
        Map<String, List<PerformedSet>> byExercise = scan(userId);
        Set<String> wanted = new HashSet<>(exerciseIds);
        LocalDate today = LocalDate.now();
        Map<String, ExerciseDigest> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<PerformedSet>> e : byExercise.entrySet()) {
            if (!wanted.contains(e.getKey())) continue;
            ExerciseDigest d = build(e.getKey(), e.getValue(), today);
            if (d != null) out.put(e.getKey(), d);
        }
        return out;
    }

    /** A digest for every exerciseId that appears anywhere in the user's history. */
    public Map<String, ExerciseDigest> digestAll(String userId) {
        Map<String, List<PerformedSet>> byExercise = scan(userId);
        LocalDate today = LocalDate.now();
        Map<String, ExerciseDigest> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<PerformedSet>> e : byExercise.entrySet()) {
            ExerciseDigest d = build(e.getKey(), e.getValue(), today);
            if (d != null) out.put(e.getKey(), d);
        }
        return out;
    }

    /**
     * The most recent {@code limit} logged sets for one exercise across all
     * programs (incl. archived), newest date first and, within a date, newest
     * {@code completedAt} first. Backs the {@code get_exercise_history} tool.
     */
    public List<ExerciseSetLog> history(String userId, String exerciseId, int limit) {
        List<PerformedSet> sets = scan(userId).getOrDefault(exerciseId, List.of());
        return sets.stream()
            .sorted(Comparator
                .comparing((PerformedSet p) -> p.date)
                .thenComparing(p -> p.completedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .reversed())
            .limit(Math.max(0, limit))
            .map(p -> new ExerciseSetLog(p.date, p.weightLbs, p.reps, p.rpe, p.programId))
            .toList();
    }

    /**
     * The sets performed the LAST time each requested exercise was done — the
     * most recent COMPLETED date for that exercise, in performed order. Ids with
     * no history are omitted. Backs the live coach's "same as last time" prefill
     * (IMPL-COACH PR2): the literal previous session, not a designed target.
     */
    public Map<String, List<LoggedSet>> lastSessionSets(String userId, Collection<String> exerciseIds) {
        Map<String, List<PerformedSet>> byExercise = scan(userId);
        Map<String, List<LoggedSet>> out = new LinkedHashMap<>();
        for (String exerciseId : new HashSet<>(exerciseIds)) {
            List<PerformedSet> sets = byExercise.get(exerciseId);
            if (sets == null || sets.isEmpty()) continue;
            LocalDate lastDate = sets.stream()
                .map(p -> p.date)
                .filter(d -> d != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
            if (lastDate == null) continue;
            List<LoggedSet> lastSets = sets.stream()
                .filter(p -> lastDate.equals(p.date))
                .sorted(Comparator.comparing(
                    (PerformedSet p) -> p.completedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())))
                .map(p -> new LoggedSet(p.weightLbs, p.reps, p.rpe, null, p.completedAt))
                .toList();
            if (!lastSets.isEmpty()) out.put(exerciseId, lastSets);
        }
        return out;
    }

    // ---- per-user scan (cached) ----

    /**
     * Scan every program's COMPLETED sessions and flatten their logged sets into
     * a map keyed by exerciseId. Cached by userId so a chat burst reuses one
     * scan; the public methods filter/derive over the cached raw data.
     */
    @Cacheable(cacheNames = CacheConfig.EXERCISE_DIGEST, key = "#userId")
    Map<String, List<PerformedSet>> scan(String userId) {
        Map<String, List<PerformedSet>> byExercise = new LinkedHashMap<>();
        for (WorkoutProgram program : nullSafe(programs.findByUserIncludingArchived(userId))) {
            if (program == null || program.programId() == null) continue;
            List<ScheduledWorkout> sessions =
                scheduled.findByProgram(userId, program.programId(), LocalDate.MIN, LocalDate.MAX);
            for (ScheduledWorkout sw : nullSafe(sessions)) {
                if (sw == null || sw.status() != ScheduledStatus.COMPLETED || sw.session() == null) continue;
                LocalDate date = sw.date();
                if (date == null) continue;
                for (Block block : nullSafe(sw.session().blocks())) {
                    if (block == null) continue;
                    for (Prescription rx : nullSafe(block.prescriptions())) {
                        if (rx == null || rx.exerciseId() == null) continue;
                        for (LoggedSet set : nullSafe(rx.loggedSets())) {
                            if (set == null) continue;
                            byExercise
                                .computeIfAbsent(rx.exerciseId(), k -> new ArrayList<>())
                                .add(new PerformedSet(
                                    date, set.weightLbs(), set.reps(), set.rpe(),
                                    set.completedAt(), program.programId()));
                        }
                    }
                }
            }
        }
        return byExercise;
    }

    // ---- derivation ----

    private static ExerciseDigest build(String exerciseId, List<PerformedSet> sets, LocalDate today) {
        if (sets == null || sets.isEmpty()) return null;

        LocalDate lastPerformed = null;
        Integer minReps = null;
        Integer maxReps = null;
        double rpeSum = 0;
        int rpeCount = 0;
        int trailing4wk = 0;
        int prior4wk = 0;

        // e1RM selection: prefer the best weight+reps set in the window; track a
        // weight-only fallback for the low-confidence floor.
        PerformedSet bestRepped = null;       // highest e1RM, in window
        double bestReppedE1rm = Double.NEGATIVE_INFINITY;
        PerformedSet bestReppedAllTime = null;
        double bestReppedAllTimeE1rm = Double.NEGATIVE_INFINITY;
        PerformedSet heaviestWeightOnly = null;   // any qualifying weight, reps null

        for (PerformedSet s : sets) {
            if (lastPerformed == null || s.date.isAfter(lastPerformed)) lastPerformed = s.date;

            if (s.reps != null) {
                if (minReps == null || s.reps < minReps) minReps = s.reps;
                if (maxReps == null || s.reps > maxReps) maxReps = s.reps;
            }
            if (s.rpe != null) {
                rpeSum += s.rpe;
                rpeCount++;
            }

            long daysAgo = ChronoUnit.DAYS.between(s.date, today);
            if (daysAgo >= 0 && daysAgo < TRAILING_DAYS) trailing4wk++;
            else if (daysAgo >= TRAILING_DAYS && daysAgo < PRIOR_DAYS) prior4wk++;

            if (s.weightLbs != null && s.reps != null) {
                double e1rm = s.weightLbs * (1 + s.reps / 30.0);
                if (e1rm > bestReppedAllTimeE1rm) {
                    bestReppedAllTimeE1rm = e1rm;
                    bestReppedAllTime = s;
                }
                long weeksAgo = ChronoUnit.WEEKS.between(s.date, today);
                if (weeksAgo <= E1RM_WINDOW_WEEKS && e1rm > bestReppedE1rm) {
                    bestReppedE1rm = e1rm;
                    bestRepped = s;
                }
            } else if (s.weightLbs != null) {
                if (heaviestWeightOnly == null
                    || s.weightLbs > heaviestWeightOnly.weightLbs) {
                    heaviestWeightOnly = s;
                }
            }
        }

        Double estimated1Rm = null;
        Double bestRecentWeight = null;
        Integer bestRecentReps = null;
        boolean lowConfidence = false;

        PerformedSet chosen = bestRepped != null ? bestRepped : bestReppedAllTime;
        if (chosen != null) {
            estimated1Rm = chosen.weightLbs * (1 + chosen.reps / 30.0);
            bestRecentWeight = chosen.weightLbs;
            bestRecentReps = chosen.reps;
        } else if (heaviestWeightOnly != null) {
            // Only weight-only imported rows informed the estimate: a
            // conservative low-confidence floor (R2).
            estimated1Rm = heaviestWeightOnly.weightLbs;
            bestRecentWeight = heaviestWeightOnly.weightLbs;
            bestRecentReps = null;
            lowConfidence = true;
        }

        Integer weeksSinceLast = lastPerformed == null
            ? null
            : (int) ChronoUnit.WEEKS.between(lastPerformed, today);
        Double typicalRpe = rpeCount == 0 ? null : rpeSum / rpeCount;

        return new ExerciseDigest(
            exerciseId, lastPerformed, weeksSinceLast,
            bestRecentWeight, bestRecentReps, estimated1Rm, lowConfidence,
            typicalRpe, minReps, maxReps, trailing4wk, prior4wk);
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    /** Flattened performed set, internal to the scan/derivation fold. */
    record PerformedSet(
        LocalDate date, Double weightLbs, Integer reps, Double rpe,
        Instant completedAt, String programId
    ) {}
}
