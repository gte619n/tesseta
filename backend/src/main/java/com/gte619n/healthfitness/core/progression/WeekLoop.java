package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.exercise.Exercise;
import com.gte619n.healthfitness.core.exercise.ExerciseRepository;
import com.gte619n.healthfitness.core.exercise.MovementPattern;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The week loop (IMPL-PROG-01 §7): at microcycle end, reads e1RM TREND (never
 * the absolute value) and a fatigue index, and adjusts {@code weeklySetTarget}
 * per movement pattern plus deload flags. Deliberately dumb — the volume
 * dose-response is shallow (§7.3). Load stays the session loop's alone.
 *
 * <p>Fatigue index is rep drop-off at constant load within a session (D16), NOT
 * intermediate-set RIR. Deficit suppression (D11): when the block success
 * criterion is HOLD_LOAD_AT_LOWER_RIR, a FLAT trend is success and must not
 * deload.
 */
@Service
public class WeekLoop {

    static final int TREND_WINDOW_DAYS = 21;
    /** Weekly e1RM slope (fraction of baseline) above/below which is RISING/FALLING. */
    static final double RISE_THRESHOLD = 0.005;   // +0.5%/week
    static final double FALL_THRESHOLD = -0.005;  // −0.5%/week
    static final int MIN_SETS = 4;

    private final SetObservationRepository observations;
    private final ExerciseRepository exercises;
    private final BlockParametersRepository blockParams;
    private final WeekParametersRepository weekParams;
    private final Clock clock;

    public WeekLoop(
        SetObservationRepository observations,
        ExerciseRepository exercises,
        BlockParametersRepository blockParams,
        WeekParametersRepository weekParams,
        Clock clock
    ) {
        this.observations = observations;
        this.exercises = exercises;
        this.blockParams = blockParams;
        this.weekParams = weekParams;
        this.clock = clock;
    }

    /** One condensed view per movement pattern — exposed for the week-review API. */
    public record PatternReview(
        MovementPattern pattern, Trend trend, double weeklySlopeFraction,
        double fatigueIndex, int currentTarget, int proposedTarget, boolean deload, String reasoning) {}

    /** Compute reviews for every pattern the user has trained recently (no writes). */
    public List<PatternReview> review(String userId) {
        BlockParameters block = blockParams.find(userId).orElseGet(() -> BlockParameters.defaults(userId));
        WeekParameters prior = weekParams.find(userId).orElse(null);
        Instant now = clock.instant();
        Instant windowStart = now.minus(Duration.ofDays(TREND_WINDOW_DAYS));

        Map<MovementPattern, List<SetObservation>> byPattern = groupByPattern(userId, windowStart);
        List<PatternReview> out = new ArrayList<>();
        for (Map.Entry<MovementPattern, List<SetObservation>> e : byPattern.entrySet()) {
            MovementPattern pattern = e.getKey();
            List<SetObservation> obs = e.getValue();
            double slope = weeklySlopeFraction(obs, now);
            Trend trend = classify(slope);
            double fatigue = fatigueIndex(obs);

            int current = prior != null ? prior.weeklySetTarget(pattern, countWorkingSets(obs)) : countWorkingSets(obs);
            int ceiling = block.ceiling(pattern);
            boolean suppressed = block.successCriterion() == SuccessCriterion.HOLD_LOAD_AT_LOWER_RIR;

            int proposed = current;
            boolean deload = false;
            String why;
            if (trend == Trend.FALLING) {
                deload = true;
                proposed = Math.max(MIN_SETS, current / 2);
                why = "falling trend → deload (½ volume, hold load, +2 RIR)";
            } else if (trend == Trend.FLAT && fatigue < -0.5 && !suppressed) {
                deload = true;
                proposed = Math.max(MIN_SETS, current / 2);
                why = "flat with rising fatigue → deload";
            } else if (trend == Trend.FLAT && suppressed) {
                why = "flat in a deficit = success → hold volume (no deload)";
            } else if (trend == Trend.FLAT) {
                proposed = Math.min(ceiling, current + 1);
                why = "flat, recovered → +1 set";
            } else if (trend == Trend.RISING) {
                why = "rising → hold volume";
            } else {
                why = "not enough data → hold";
            }
            out.add(new PatternReview(pattern, trend, slope, fatigue, current, proposed, deload, why));
        }
        return out;
    }

    /** Compute reviews and PERSIST the resulting week parameters. */
    public WeekParameters recompute(String userId) {
        List<PatternReview> reviews = review(userId);
        Map<MovementPattern, Integer> targets = new EnumMap<>(MovementPattern.class);
        Set<MovementPattern> deloads = new HashSet<>();
        for (PatternReview r : reviews) {
            targets.put(r.pattern(), r.proposedTarget());
            if (r.deload()) deloads.add(r.pattern());
        }
        WeekParameters params = new WeekParameters(userId, targets, deloads, clock.instant());
        weekParams.save(params);
        return params;
    }

    // ---- trend ----

    private Map<MovementPattern, List<SetObservation>> groupByPattern(String userId, Instant windowStart) {
        List<SetObservation> recent = observations.findByUserSince(userId, windowStart);
        Set<String> exIds = new HashSet<>();
        for (SetObservation o : recent) exIds.add(o.exerciseId());
        Map<String, MovementPattern> patternOf = new HashMap<>();
        for (Exercise ex : exercises.findByIds(exIds)) {
            patternOf.put(ex.exerciseId(), ex.movementPattern() == null ? MovementPattern.OTHER : ex.movementPattern());
        }
        Map<MovementPattern, List<SetObservation>> byPattern = new EnumMap<>(MovementPattern.class);
        for (SetObservation o : recent) {
            MovementPattern p = patternOf.getOrDefault(o.exerciseId(), MovementPattern.OTHER);
            byPattern.computeIfAbsent(p, k -> new ArrayList<>()).add(o);
        }
        return byPattern;
    }

    /**
     * Pooled weekly e1RM slope as a fraction of baseline. Each observation's
     * last-working-set Epley e1RM (normalized to that exercise's first point in
     * the window) contributes a (dayOffset, ratio) point; OLS slope × 7 = weekly.
     */
    static double weeklySlopeFraction(List<SetObservation> obs, Instant now) {
        // Per exercise: baseline = earliest e1RM in window; points = e1rm/baseline.
        Map<String, Double> baseline = new HashMap<>();
        Map<String, Instant> baseTime = new HashMap<>();
        List<double[]> points = new ArrayList<>();
        List<SetObservation> sorted = new ArrayList<>(obs);
        sorted.sort((a, b) -> nz(a.completedAt()).compareTo(nz(b.completedAt())));
        for (SetObservation o : sorted) {
            if (!o.isLastWorkingSet() || o.reps() == null || o.rir() == null) continue;
            double e1rm = ProgressionMath.epleyE1rm(o.load(), o.reps() + o.rir());
            baseline.putIfAbsent(o.exerciseId(), e1rm);
            baseTime.putIfAbsent(o.exerciseId(), nz(o.completedAt()));
            double base = baseline.get(o.exerciseId());
            if (base <= 0) continue;
            double days = ChronoUnit.SECONDS.between(baseTime.get(o.exerciseId()), nz(o.completedAt())) / 86400.0;
            points.add(new double[]{days, e1rm / base});
        }
        double slopePerDay = olsSlope(points);
        return slopePerDay * 7.0;
    }

    static Trend classify(double weeklySlopeFraction) {
        if (Double.isNaN(weeklySlopeFraction)) return Trend.UNKNOWN;
        if (weeklySlopeFraction >= RISE_THRESHOLD) return Trend.RISING;
        if (weeklySlopeFraction <= FALL_THRESHOLD) return Trend.FALLING;
        return Trend.FLAT;
    }

    /**
     * Fatigue index: mean within-session reps slope across sets at constant load
     * (D16). Negative = reps falling across sets = fatigue; a more negative value
     * week-over-week means fatigue accumulating.
     */
    static double fatigueIndex(List<SetObservation> obs) {
        Map<String, List<SetObservation>> bySession = new HashMap<>();
        for (SetObservation o : obs) {
            if (o.reps() == null) continue;
            bySession.computeIfAbsent(o.sessionId() + "|" + o.exerciseId(), k -> new ArrayList<>()).add(o);
        }
        List<Double> slopes = new ArrayList<>();
        for (List<SetObservation> sets : bySession.values()) {
            if (sets.size() < 2) continue;
            sets.sort((a, b) -> Integer.compare(a.setIndex(), b.setIndex()));
            List<double[]> points = new ArrayList<>();
            for (SetObservation s : sets) points.add(new double[]{s.setIndex(), s.reps()});
            double slope = olsSlope(points);
            if (!Double.isNaN(slope)) slopes.add(slope);
        }
        if (slopes.isEmpty()) return 0;
        return slopes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static int countWorkingSets(List<SetObservation> obs) {
        long n = obs.stream().filter(o -> o.reps() != null).count();
        // sets performed in the window ≈ target; floor at MIN_SETS.
        return Math.max(MIN_SETS, (int) Math.round(n / Math.max(1.0, distinctSessions(obs))));
    }

    private static long distinctSessions(List<SetObservation> obs) {
        return obs.stream().map(SetObservation::sessionId).distinct().count();
    }

    /** Ordinary-least-squares slope of y over x; NaN if fewer than 2 points or zero variance. */
    static double olsSlope(List<double[]> points) {
        int n = points.size();
        if (n < 2) return Double.NaN;
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (double[] p : points) {
            sx += p[0]; sy += p[1]; sxx += p[0] * p[0]; sxy += p[0] * p[1];
        }
        double denom = n * sxx - sx * sx;
        if (Math.abs(denom) < 1e-9) return Double.NaN;
        return (n * sxy - sx * sy) / denom;
    }

    private static Instant nz(Instant i) {
        return i == null ? Instant.EPOCH : i;
    }
}
