package com.gte619n.healthfitness.core.workoutstats;

import com.gte619n.healthfitness.config.CacheConfig;
import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import com.gte619n.healthfitness.core.progression.LoadConventionResolver;
import com.gte619n.healthfitness.core.progression.ProgressionState;
import com.gte619n.healthfitness.core.progression.ProgressionStateRepository;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.LoggedSet;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkoutRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramRepository;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettings;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutSettingsService;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

/**
 * Computes the web Overview read-model (IMPL-WEB-WORKOUT-01 §5) from the user's
 * COMPLETED sessions across every program (including archived/imported history).
 * The one Firestore-touching step — {@link #scan(String)} — is cached per user
 * for a short TTL; every public method derives its answer as a pure fold over
 * the cached scan, so the derivation honors the request's local {@code today}
 * and {@code weeks} without multiplying cache entries.
 *
 * <p>Streak semantics (§5.1): a week counts when it has ≥ {@code weeklyTarget}
 * COMPLETED sessions; ISO weeks start Monday in the caller's zone. The current
 * in-progress week is reported separately as progress and can only extend the
 * streak (never break it) until it ends.
 *
 * <p>e1RM (§5.3) is Epley {@code w×(1+reps/30)} on each session's best set;
 * weight-only imported rows (reps null) degrade to the weight itself with
 * {@code lowConfidence=true} and can never mint a PR (§5.4).
 */
@Service
public class WorkoutStatsService {

    /** Heatmap window: roughly the last six months. */
    private static final int HEATMAP_DAYS = 183;
    /** Default length of the weekly volume series; clamped to [MIN,MAX]. */
    public static final int DEFAULT_WEEKS = 26;
    public static final int MIN_WEEKS = 4;
    public static final int MAX_WEEKS = 104;
    /** How many recent PRs the Overview card shows. */
    private static final int RECENT_PR_LIMIT = 5;
    /** How many lifts the strength chart defaults to. */
    private static final int DEFAULT_LIFT_LIMIT = 4;

    /** Movement patterns the default-lift picker treats as "main" (one each). */
    private static final Set<MovementPattern> MAIN_PATTERNS = EnumSet.of(
        MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.LUNGE,
        MovementPattern.PUSH_HORIZONTAL, MovementPattern.PUSH_VERTICAL,
        MovementPattern.PULL_HORIZONTAL, MovementPattern.PULL_VERTICAL);

    private final WorkoutProgramRepository programs;
    private final ScheduledWorkoutRepository scheduled;
    private final WorkoutSettingsService settings;
    private final ExerciseRepository exercises;
    private final ProgressionStateRepository states;
    private final LoadConventionResolver conventions;

    public WorkoutStatsService(
        WorkoutProgramRepository programs,
        ScheduledWorkoutRepository scheduled,
        WorkoutSettingsService settings,
        ExerciseRepository exercises,
        ProgressionStateRepository states,
        LoadConventionResolver conventions
    ) {
        this.programs = programs;
        this.scheduled = scheduled;
        this.settings = settings;
        this.exercises = exercises;
        this.states = states;
        this.conventions = conventions;
    }

    // ---- public read model ----

    /**
     * The full Overview stats bundle for {@code today} (the caller's local date)
     * with a {@code weeks}-long volume series.
     */
    public WorkoutStats stats(String userId, LocalDate today, int weeks) {
        int series = Math.max(MIN_WEEKS, Math.min(MAX_WEEKS, weeks));
        Scan scan = scan(userId);
        int target = weeklyTarget(userId);

        Map<String, String> names = exerciseNames(scan.bestByExercise.keySet());

        WorkoutStats.Streak streak = streak(scan.sessions, target, today);
        List<WorkoutStats.WeekPoint> weekly = weeklySeries(scan.sessions, today, series);
        List<WorkoutStats.HeatmapDay> heatmap = heatmap(scan.sessions, today);
        List<WorkoutStats.PrPoint> prs = recentPrs(scan.bestByExercise, names, scan.factorByExercise);
        List<WorkoutStats.LiftRef> defaults = chartDefaultLifts(userId);
        List<WorkoutStats.TrackedExercise> tracked = trackedExercises(scan.bestByExercise, names);

        return new WorkoutStats(streak, weekly, heatmap, prs, defaults, tracked);
    }

    /** The estimated-1RM curve + current belief for one exercise (§5.3). */
    public E1rmHistory e1rmHistory(String userId, String exerciseId) {
        Scan scan = scan(userId);
        String name = exerciseNames(Set.of(exerciseId)).getOrDefault(exerciseId, exerciseId);
        int factor = scan.factorByExercise.getOrDefault(exerciseId,
            conventions.factor(userId, exerciseId));
        List<SessionBest> bests = scan.bestByExercise.getOrDefault(exerciseId, List.of());
        List<E1rmHistory.Point> points = new ArrayList<>();
        for (SessionBest b : bests) {
            Double weightTotal = b.weightLbs == null ? null : b.weightLbs * factor;
            points.add(new E1rmHistory.Point(
                b.date, b.e1rm, b.weightLbs, b.reps, b.lowConfidence, b.e1rm * factor, weightTotal));
        }
        E1rmHistory.Belief belief = states.find(userId, exerciseId)
            .filter(s -> s.e1rmLbs() > 0)
            .map(s -> new E1rmHistory.Belief(s.e1rmLbs(), s.sigmaLbs(), s.confidence().name()))
            .orElse(null);
        return new E1rmHistory(exerciseId, name, points, belief, factor);
    }

    /**
     * The block/prescription/set keys of the PR sets in one session — the sets a
     * session-detail view badges. Key format {@code "blockId:orderIndex:setIndex"}
     * (IL-8), matching the render tree exactly. Empty when the session set no PRs.
     */
    public List<String> prSetKeys(String userId, String programId, String scheduledId) {
        Scan scan = scan(userId);
        List<String> keys = new ArrayList<>();
        for (List<SessionBest> bests : scan.bestByExercise.values()) {
            SessionBest pr = firstMatchingPr(bests, programId, scheduledId);
            if (pr != null) keys.add(pr.blockId + ":" + pr.orderIndex + ":" + pr.setIndex);
        }
        return keys;
    }

    /**
     * The COMPLETED sessions immediately older ({@code prev}) and newer
     * ({@code next}) than the given one, over the union of all programs, ordered
     * by (date, completedAt, scheduledId) — matching the History view's
     * cross-program ordering (IL-7). Either side is null at the ends; both are
     * null when the target session isn't in history.
     */
    public Neighbors neighbors(String userId, String programId, String scheduledId) {
        List<CompletedSession> ordered = new ArrayList<>(scan(userId).sessions);
        ordered.sort(Comparator
            .comparing((CompletedSession s) -> s.date)
            .thenComparing(s -> s.completedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(s -> safe(s.scheduledId)));
        int idx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            CompletedSession s = ordered.get(i);
            if (safe(programId).equals(s.programId) && safe(scheduledId).equals(s.scheduledId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) return new Neighbors(null, null);
        NeighborRef prev = idx > 0 ? ref(ordered.get(idx - 1)) : null;
        NeighborRef next = idx < ordered.size() - 1 ? ref(ordered.get(idx + 1)) : null;
        return new Neighbors(prev, next);
    }

    private static NeighborRef ref(CompletedSession s) {
        return new NeighborRef(s.programId, s.scheduledId, s.date);
    }

    /** A pointer to an adjacent session for prev/next navigation. */
    public record NeighborRef(String programId, String scheduledId, LocalDate date) {}

    /** The sessions bracketing one session in cross-program history. */
    public record Neighbors(NeighborRef prev, NeighborRef next) {}

    // ---- derivation: streak / series / heatmap ----

    static WorkoutStats.Streak streak(List<CompletedSession> sessions, int target, LocalDate today) {
        LocalDate currentMonday = monday(today);
        Map<LocalDate, Integer> countByWeek = new HashMap<>();
        LocalDate earliest = null;
        for (CompletedSession s : sessions) {
            LocalDate wk = monday(s.date);
            countByWeek.merge(wk, 1, Integer::sum);
            if (earliest == null || wk.isBefore(earliest)) earliest = wk;
        }
        int thisWeek = countByWeek.getOrDefault(currentMonday, 0);

        // Current streak: walk back from the last fully-elapsed week; the current
        // week extends it (when it already meets target) but can never break it.
        int current = 0;
        for (LocalDate wk = currentMonday.minusWeeks(1);
             countByWeek.getOrDefault(wk, 0) >= target; wk = wk.minusWeeks(1)) {
            current++;
        }
        if (thisWeek >= target) current++;

        // Longest run over all history, applying the same current-week rule.
        int longest = 0;
        if (earliest != null) {
            int run = 0;
            for (LocalDate wk = earliest; !wk.isAfter(currentMonday); wk = wk.plusWeeks(1)) {
                boolean qualifies = wk.equals(currentMonday)
                    ? thisWeek >= target
                    : countByWeek.getOrDefault(wk, 0) >= target;
                run = qualifies ? run + 1 : 0;
                if (run > longest) longest = run;
            }
        }
        return new WorkoutStats.Streak(current, longest, target, thisWeek, currentMonday);
    }

    static List<WorkoutStats.WeekPoint> weeklySeries(
        List<CompletedSession> sessions, LocalDate today, int weeks) {
        LocalDate currentMonday = monday(today);
        Map<LocalDate, int[]> countByWeek = new HashMap<>();
        Map<LocalDate, double[]> tonnageByWeek = new HashMap<>();
        for (CompletedSession s : sessions) {
            LocalDate wk = monday(s.date);
            countByWeek.computeIfAbsent(wk, k -> new int[1])[0]++;
            tonnageByWeek.computeIfAbsent(wk, k -> new double[1])[0] += s.tonnage;
        }
        List<WorkoutStats.WeekPoint> out = new ArrayList<>();
        LocalDate first = currentMonday.minusWeeks(weeks - 1L);
        for (LocalDate wk = first; !wk.isAfter(currentMonday); wk = wk.plusWeeks(1)) {
            int c = countByWeek.containsKey(wk) ? countByWeek.get(wk)[0] : 0;
            double t = tonnageByWeek.containsKey(wk) ? tonnageByWeek.get(wk)[0] : 0.0;
            out.add(new WorkoutStats.WeekPoint(wk, c, t));
        }
        return out;
    }

    static List<WorkoutStats.HeatmapDay> heatmap(List<CompletedSession> sessions, LocalDate today) {
        LocalDate from = today.minusDays(HEATMAP_DAYS - 1L);
        // date -> (count, best representative session)
        Map<LocalDate, int[]> counts = new HashMap<>();
        Map<LocalDate, CompletedSession> firstByDay = new LinkedHashMap<>();
        for (CompletedSession s : sessions) {
            if (s.date.isBefore(from) || s.date.isAfter(today)) continue;
            counts.computeIfAbsent(s.date, k -> new int[1])[0]++;
            CompletedSession prev = firstByDay.get(s.date);
            if (prev == null || representativeBefore(s, prev)) {
                firstByDay.put(s.date, s);
            }
        }
        List<WorkoutStats.HeatmapDay> out = new ArrayList<>();
        firstByDay.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(e -> {
                CompletedSession s = e.getValue();
                out.add(new WorkoutStats.HeatmapDay(
                    e.getKey(), counts.get(e.getKey())[0],
                    new WorkoutStats.SessionRef(s.programId, s.scheduledId), s.isDeload));
            });
        return out;
    }

    /** The earliest session on a day, ties broken by scheduledId, for a stable "first". */
    private static boolean representativeBefore(CompletedSession a, CompletedSession b) {
        int byTime = Comparator.<Instant>nullsLast(Comparator.naturalOrder())
            .compare(a.completedAt, b.completedAt);
        if (byTime != 0) return byTime < 0;
        return safe(a.scheduledId).compareTo(safe(b.scheduledId)) < 0;
    }

    // ---- derivation: PRs / lifts ----

    static List<WorkoutStats.PrPoint> recentPrs(
        Map<String, List<SessionBest>> bestByExercise, Map<String, String> names,
        Map<String, Integer> factors) {
        List<WorkoutStats.PrPoint> all = new ArrayList<>();
        for (Map.Entry<String, List<SessionBest>> e : bestByExercise.entrySet()) {
            String id = e.getKey();
            String name = names.getOrDefault(id, id);
            int factor = factors.getOrDefault(id, 1);
            for (SessionBest pr : personalRecords(e.getValue())) {
                double weight = pr.weightLbs == null ? 0.0 : pr.weightLbs;
                all.add(new WorkoutStats.PrPoint(
                    id, name, pr.e1rm, weight, pr.reps, pr.date, pr.programId, pr.scheduledId,
                    factor, pr.e1rm * factor, weight * factor));
            }
        }
        all.sort(Comparator
            .comparing(WorkoutStats.PrPoint::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed());
        return all.size() > RECENT_PR_LIMIT ? new ArrayList<>(all.subList(0, RECENT_PR_LIMIT)) : all;
    }

    /**
     * The PR sessions in one exercise's chronological session-bests: a session
     * whose best-set e1RM strictly exceeds every prior session's best, has at
     * least one prior session, and whose qualifying set has non-null reps (§5.4).
     * Weight-only rows still raise the running best a later PR must beat.
     */
    private static List<SessionBest> personalRecords(List<SessionBest> bests) {
        List<SessionBest> prs = new ArrayList<>();
        double runningBest = Double.NEGATIVE_INFINITY;
        boolean priorSeen = false;
        for (SessionBest s : bests) {
            if (priorSeen && s.reps != null && s.e1rm > runningBest) {
                prs.add(s);
            }
            if (s.e1rm > runningBest) runningBest = s.e1rm;
            priorSeen = true;
        }
        return prs;
    }

    private static SessionBest firstMatchingPr(
        List<SessionBest> bests, String programId, String scheduledId) {
        for (SessionBest pr : personalRecords(bests)) {
            if (safe(programId).equals(pr.programId) && safe(scheduledId).equals(pr.scheduledId)) {
                return pr;
            }
        }
        return null;
    }

    List<WorkoutStats.LiftRef> chartDefaultLifts(String userId) {
        List<ProgressionState> all = states.findAll(userId).stream()
            .filter(s -> s.e1rmLbs() > 0)
            .sorted(Comparator.comparingInt(ProgressionState::observationCount).reversed())
            .toList();
        Map<String, Exercise> byId = exerciseCatalogFor(all.stream().map(ProgressionState::exerciseId).toList());
        List<WorkoutStats.LiftRef> out = new ArrayList<>();
        Set<MovementPattern> usedPatterns = EnumSet.noneOf(MovementPattern.class);
        for (ProgressionState s : all) {
            if (out.size() >= DEFAULT_LIFT_LIMIT) break;
            Exercise ex = byId.get(s.exerciseId());
            MovementPattern pattern = ex == null ? null : ex.movementPattern();
            if (pattern == null || !MAIN_PATTERNS.contains(pattern) || !usedPatterns.add(pattern)) {
                continue;
            }
            out.add(new WorkoutStats.LiftRef(s.exerciseId(), ex.name()));
        }
        return out;
    }

    static List<WorkoutStats.TrackedExercise> trackedExercises(
        Map<String, List<SessionBest>> bestByExercise, Map<String, String> names) {
        List<WorkoutStats.TrackedExercise> out = new ArrayList<>();
        for (Map.Entry<String, List<SessionBest>> e : bestByExercise.entrySet()) {
            // A single session is a data point, not a trend: the picker only lists
            // exercises the chart can actually draw a line for (≥2 sessions), so it
            // stays a short, meaningful list rather than every exercise ever logged.
            if (e.getValue().size() < 2) continue;
            // Strength picker only (the chart is an e1RM trend): require at least one
            // weighted set (e1rm > 0). Bodyweight / stretch / cardio movements logged
            // at 0 lb produce e1rm=0 bests and carry no strength signal, so they'd
            // otherwise flood the picker with un-chartable lines.
            if (e.getValue().stream().noneMatch(b -> b.e1rm > 0)) continue;
            String id = e.getKey();
            LocalDate last = e.getValue().stream()
                .map(b -> b.date).filter(d -> d != null).max(Comparator.naturalOrder()).orElse(null);
            out.add(new WorkoutStats.TrackedExercise(id, names.getOrDefault(id, id), last));
        }
        out.sort(Comparator
            .comparing(WorkoutStats.TrackedExercise::lastPerformed,
                Comparator.nullsLast(Comparator.naturalOrder()))
            .reversed());
        return out;
    }

    // ---- per-user scan (cached) ----

    /**
     * Scan every program's COMPLETED sessions once and flatten into (a) per-session
     * summaries (date, refs, tonnage, logged-set count) and (b) per-exercise
     * chronological session-bests (the max-e1RM set of each session it appears in,
     * with its render-tree location for PR badging). Cached by userId; the public
     * methods derive over this raw data. Mirrors
     * {@link com.gte619n.healthfitness.core.workoutprogram.ExercisePerformanceDigestService#scan}.
     */
    @Cacheable(cacheNames = CacheConfig.WORKOUT_STATS, key = "#userId")
    Scan scan(String userId) {
        // Collect completed rows once, gathering the exercise ids they reference so
        // the per-hand→total load factor (IMPL-PROG-LOAD-01 IL-10) can be resolved
        // in a single batch before we compute tonnage.
        List<Row> rows = new ArrayList<>();
        Set<String> exerciseIds = new java.util.LinkedHashSet<>();
        for (WorkoutProgram program : nullSafe(programs.findByUserIncludingArchived(userId))) {
            if (program == null || program.programId() == null) continue;
            List<ScheduledWorkout> found =
                scheduled.findByProgram(userId, program.programId(), LocalDate.MIN, LocalDate.MAX);
            for (ScheduledWorkout sw : nullSafe(found)) {
                if (sw == null || sw.status() != ScheduledStatus.COMPLETED || sw.session() == null) continue;
                LocalDate date = sw.date();
                if (date == null) continue;
                rows.add(new Row(program.programId(), sw, date));
                for (Block block : nullSafe(sw.session().blocks())) {
                    if (block == null) continue;
                    for (Prescription rx : nullSafe(block.prescriptions())) {
                        if (rx != null && rx.exerciseId() != null) exerciseIds.add(rx.exerciseId());
                    }
                }
            }
        }
        Map<String, Integer> factors = conventions.factors(userId, exerciseIds);

        List<CompletedSession> sessions = new ArrayList<>();
        Map<String, List<SessionBest>> bestByExercise = new LinkedHashMap<>();
        for (Row row : rows) {
            indexSession(sessions, bestByExercise, row.programId, row.sw, row.date, factors);
        }
        // Order each exercise's bests chronologically for the PR walk and curve.
        for (List<SessionBest> list : bestByExercise.values()) {
            list.sort(Comparator
                .comparing((SessionBest b) -> b.date)
                .thenComparing(b -> b.completedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
        }
        return new Scan(sessions, bestByExercise, factors);
    }

    private static void indexSession(
        List<CompletedSession> sessions,
        Map<String, List<SessionBest>> bestByExercise,
        String programId, ScheduledWorkout sw, LocalDate date, Map<String, Integer> factors) {

        double tonnage = 0.0;
        int loggedSetCount = 0;
        // Per exercise within THIS session: track the best set + its location.
        Map<String, SessionBest> bestInSession = new LinkedHashMap<>();

        for (Block block : nullSafe(sw.session().blocks())) {
            if (block == null) continue;
            for (Prescription rx : nullSafe(block.prescriptions())) {
                if (rx == null || rx.exerciseId() == null) continue;
                int factor = factors.getOrDefault(rx.exerciseId(), 1);
                List<LoggedSet> logged = nullSafe(rx.loggedSets());
                for (int i = 0; i < logged.size(); i++) {
                    LoggedSet set = logged.get(i);
                    if (set == null) continue;
                    loggedSetCount++;
                    if (set.weightLbs() != null && set.reps() != null) {
                        // Total-load tonnage: per-hand lifts count double (D10).
                        tonnage += set.weightLbs() * factor * set.reps();
                    }
                    SessionBest candidate = candidate(
                        rx.exerciseId(), date, sw, programId, set, block.blockId(), rx.orderIndex(), i);
                    if (candidate == null) continue;
                    SessionBest prev = bestInSession.get(rx.exerciseId());
                    if (prev == null || candidate.e1rm > prev.e1rm) {
                        bestInSession.put(rx.exerciseId(), candidate);
                    }
                }
            }
        }
        for (Map.Entry<String, SessionBest> e : bestInSession.entrySet()) {
            bestByExercise.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
        }
        sessions.add(new CompletedSession(
            date, programId, sw.scheduledId(), sw.dayLabel(),
            sw.completedAt(), sw.durationSeconds(), sw.feeling(), loggedSetCount, tonnage,
            sw.isDeload()));
    }

    /**
     * Build a PR/curve candidate from one logged set: Epley e1RM when weight+reps
     * present, else the weight itself with low confidence. Sets with no usable
     * weight are skipped (a bodyweight/timed hold anchors nothing).
     */
    private static SessionBest candidate(
        String exerciseId, LocalDate date, ScheduledWorkout sw, String programId,
        LoggedSet set, String blockId, int orderIndex, int setIndex) {
        Double weight = set.weightLbs();
        if (weight == null) return null;
        Integer reps = set.reps();
        double e1rm;
        boolean lowConfidence;
        if (reps != null) {
            e1rm = weight * (1 + reps / 30.0);
            lowConfidence = false;
        } else {
            e1rm = weight;
            lowConfidence = true;
        }
        return new SessionBest(
            date, sw.completedAt(), programId, sw.scheduledId(),
            weight, reps, e1rm, lowConfidence, blockId, orderIndex, setIndex);
    }

    // ---- helpers ----

    private int weeklyTarget(String userId) {
        WorkoutSettings s = settings.get(userId);
        return s == null ? WorkoutSettings.DEFAULT_TARGET : s.weeklyStreakTarget();
    }

    private Map<String, String> exerciseNames(Set<String> ids) {
        Map<String, String> names = new HashMap<>();
        if (ids.isEmpty()) return names;
        for (Exercise e : exercises.findByIds(new ArrayList<>(ids))) {
            names.put(e.exerciseId(), e.name());
        }
        return names;
    }

    private Map<String, Exercise> exerciseCatalogFor(List<String> ids) {
        Map<String, Exercise> byId = new HashMap<>();
        if (ids.isEmpty()) return byId;
        for (Exercise e : exercises.findByIds(ids)) {
            byId.put(e.exerciseId(), e);
        }
        return byId;
    }

    private static LocalDate monday(LocalDate d) {
        return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    // ---- cached scan shapes (package-private for the service + tests) ----

    /** One performed session, flattened for the series/heatmap/streak folds. */
    record CompletedSession(
        LocalDate date, String programId, String scheduledId, String dayLabel,
        Instant completedAt, Integer durationSeconds, Integer feeling,
        int loggedSetCount, double tonnage, boolean isDeload) {}

    /** The best set of one exercise in one session, with its render-tree location. */
    record SessionBest(
        LocalDate date, Instant completedAt, String programId, String scheduledId,
        Double weightLbs, Integer reps, double e1rm, boolean lowConfidence,
        String blockId, int orderIndex, int setIndex) {}

    /**
     * The cached raw scan: per-session summaries + per-exercise chronological
     * bests + the per-exercise per-hand→total load factor (IMPL-PROG-LOAD-01).
     */
    record Scan(
        List<CompletedSession> sessions,
        Map<String, List<SessionBest>> bestByExercise,
        Map<String, Integer> factorByExercise) {}

    /** A completed session paired with its program id, for the two-pass scan. */
    private record Row(String programId, ScheduledWorkout sw, LocalDate date) {}
}
