package com.gte619n.healthfitness.core.goals.eval;

import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.blood.BloodReading;
import com.gte619n.healthfitness.core.blood.BloodReadingRepository;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReport;
import com.gte619n.healthfitness.core.bloodtest.BloodTestReportRepository;
import com.gte619n.healthfitness.core.bloodtest.ExtractedMarker;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.medication.AdherenceLog;
import com.gte619n.healthfitness.core.medication.AdherenceRepository;
import com.gte619n.healthfitness.core.medication.DoseLog;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.nutrition.MacroTarget;
import com.gte619n.healthfitness.core.nutrition.MacroTargetRepository;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLog;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLogRepository;
import com.gte619n.healthfitness.core.workout.WorkoutRepository;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregate;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregateRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Reads the rest of the app for the Goals evaluator.
 *
 * One {@code switch} branch per {@link MetricKey}. Each branch knows
 * which repo holds the truth for that metric and how to reduce the
 * stored shape (latest reading, panel marker, count, etc.) to a single
 * scalar. Goals code never touches these repos directly outside this
 * class.
 *
 * <h2>Stub-backed branches</h2>
 *
 * Several metrics rely on records or repos that exist as stubs in the
 * current codebase — no writer has shipped yet. Those branches return
 * {@link MetricValue#unavailable()} so {@link StepEvaluationService}
 * treats them as "no change", which preserves the Step's current
 * state. Surface area today (as of Phase 3):
 *
 * <ul>
 *   <li>{@code vitals.restingHr / hrv / sleepScore} — the
 *       {@code DailyMetricRepository} Firestore impl throws
 *       {@code UnsupportedOperationException} for both reads and
 *       writes. We catch and degrade to unavailable.</li>
 *   <li>{@code workouts.weeklyVolume} — repo exists but no producer
 *       writes aggregates; reads return empty.</li>
 * </ul>
 *
 * <p>The {@code nutrition.*Avg7d} metrics (protein/carbs/fat/calories)
 * are <strong>live</strong>: {@code NutritionService} writes
 * {@link NutritionDailyLog} rows, so these branches compute real
 * 7-day trailing averages and only return
 * {@link MetricValue#unavailable()} when the window has no logs.
 */
@Service
public class FirestoreMetricResolver implements MetricResolver {

    /**
     * Tolerance band for {@code nutrition.targetMetDays}. A consumed macro
     * counts as "on target" when it lands within ±10% of the target value.
     */
    private static final double TARGET_MET_TOLERANCE = 0.10;

    private final BloodReadingRepository bloodReadings;
    private final BloodTestReportRepository bloodTestReports;
    private final BodyCompositionRepository bodyComposition;
    private final DailyMetricRepository dailyMetrics;
    private final WorkoutRepository workouts;
    private final WeeklyWorkoutAggregateRepository weeklyAggregates;
    private final NutritionDailyLogRepository nutrition;
    private final MacroTargetRepository macroTargets;
    private final AdherenceRepository adherence;

    public FirestoreMetricResolver(
        BloodReadingRepository bloodReadings,
        BloodTestReportRepository bloodTestReports,
        BodyCompositionRepository bodyComposition,
        DailyMetricRepository dailyMetrics,
        WorkoutRepository workouts,
        WeeklyWorkoutAggregateRepository weeklyAggregates,
        NutritionDailyLogRepository nutrition,
        MacroTargetRepository macroTargets,
        AdherenceRepository adherence
    ) {
        this.bloodReadings = bloodReadings;
        this.bloodTestReports = bloodTestReports;
        this.bodyComposition = bodyComposition;
        this.dailyMetrics = dailyMetrics;
        this.workouts = workouts;
        this.weeklyAggregates = weeklyAggregates;
        this.nutrition = nutrition;
        this.macroTargets = macroTargets;
        this.adherence = adherence;
    }

    @Override
    public MetricValue resolve(String userId, MetricKey key) {
        if (key == null) return MetricValue.unavailable();
        return switch (key) {
            case BODY_WEIGHT       -> latestBodyComposition(userId, BodyCompositionMetric.WEIGHT_KG);
            case BODY_BODY_FAT_PCT -> latestBodyComposition(userId, BodyCompositionMetric.BODY_FAT_PERCENT);
            case BODY_LEAN_MASS    -> latestBodyComposition(userId, BodyCompositionMetric.LEAN_MASS_KG);

            case BLOOD_LDL    -> latestBloodMarker(userId, BloodMarker.LDL,    "LDL");
            case BLOOD_APOB   -> latestBloodMarker(userId, BloodMarker.APO_B,  "APOB");
            case BLOOD_HBA1C  -> latestBloodMarker(userId, BloodMarker.HBA1C,  "HBA1C");
            case BLOOD_HS_CRP -> latestBloodMarker(userId, BloodMarker.HS_CRP, "HSCRP");

            case VITALS_RESTING_HR  -> latestDailyMetric(userId, m -> m.restingHeartRate() != null
                ? (double) m.restingHeartRate() : null);
            case VITALS_HRV         -> latestDailyMetric(userId, m -> m.hrvMs() != null
                ? (double) m.hrvMs() : null);
            case VITALS_SLEEP_SCORE -> latestDailyMetric(userId, m -> m.sleepScore() != null
                ? (double) m.sleepScore() : null);

            // Count is window-dependent — caller goes through countSince().
            case WORKOUTS_COUNT -> MetricValue.unavailable();

            case WORKOUTS_WEEKLY_VOLUME -> latestWeeklyVolume(userId);

            case NUTRITION_PROTEIN_AVG_7D  -> nutrition7dAverage(userId, NutritionDailyLog::proteinGrams);
            case NUTRITION_CARBS_AVG_7D    -> nutrition7dAverage(userId, NutritionDailyLog::carbsGrams);
            case NUTRITION_FAT_AVG_7D      -> nutrition7dAverage(userId, NutritionDailyLog::fatGrams);
            case NUTRITION_CALORIES_AVG_7D -> nutrition7dAverage(userId, FirestoreMetricResolver::caloriesForDay);
            case NUTRITION_FIBER_AVG_7D    -> nutrition7dAverage(userId, NutritionDailyLog::fiberGrams);
            case NUTRITION_SUGAR_AVG_7D    -> nutrition7dAverage(userId, NutritionDailyLog::sugarGrams);

            // Count is window-dependent — caller goes through countSince().
            case NUTRITION_TARGET_MET_DAYS -> MetricValue.unavailable();

            case MEDS_ADHERENCE_30D -> adherence30d(userId);
        };
    }

    /**
     * SUSTAINED is a "has held for {@code windowDays}?" question.
     *
     * For metrics where we only have a latest-value snapshot
     * ({@link #resolve}), we can't replay the full window day-by-day.
     * The heuristic v1 uses: the latest value must satisfy the
     * comparator AND have a timestamp at least {@code windowDays} ago.
     * That isn't a true continuous-holds check — a regression in the
     * middle of the window would be missed — but it's adequate while
     * SUSTAINED Steps are limited to vitals and meds where the daily
     * job catches up nightly. The daily job is what drives SUSTAINED
     * truth in production (see Phase 5); on-demand evaluation just
     * approximates.
     *
     * Unavailable readings return false (condition not provably
     * sustained).
     */
    @Override
    public boolean sustainedHolds(
        String userId,
        MetricKey key,
        com.gte619n.healthfitness.core.goals.Comparator cmp,
        double target,
        int windowDays
    ) {
        if (key == null) return false;
        MetricValue current = resolve(userId, key);
        if (!current.isAvailable()) return false;
        if (current.asOf() == null) return false;
        if (!compare(current.value().get(), cmp, target)) return false;
        Instant windowStart = Instant.now().minusSeconds((long) windowDays * 86_400L);
        // The reading must be at least windowDays old AND still hold.
        return current.asOf().isBefore(windowStart);
    }

    @Override
    public long countSince(String userId, MetricKey key, Instant from) {
        if (key == null || from == null) return 0L;
        if (key == MetricKey.WORKOUTS_COUNT) {
            try {
                return workouts.findByUser(userId).stream()
                    .filter(w -> w.startTime() != null && !w.startTime().isBefore(from))
                    .count();
            } catch (UnsupportedOperationException e) {
                // Persistence stub today — see class javadoc.
                return 0L;
            }
        }
        if (key == MetricKey.NUTRITION_TARGET_MET_DAYS) {
            return targetMetDaysSince(userId, from);
        }
        // No other metrics use count-since today.
        // TODO(IMPL-12): extend if new COUNT metrics land.
        return 0L;
    }

    // ---------------- helpers ----------------

    /** Apply a {@link com.gte619n.healthfitness.core.goals.Comparator} to a value/target pair. */
    static boolean compare(double value, com.gte619n.healthfitness.core.goals.Comparator cmp, double target) {
        return switch (cmp) {
            case LT  -> value <  target;
            case LTE -> value <= target;
            case GT  -> value >  target;
            case GTE -> value >= target;
            case EQ  -> value == target;
        };
    }

    private MetricValue latestBodyComposition(String userId, BodyCompositionMetric metric) {
        try {
            List<BodyCompositionMeasurement> all = bodyComposition.findByUser(userId);
            BodyCompositionMeasurement latest = null;
            for (BodyCompositionMeasurement m : all) {
                if (m.metric() != metric) continue;
                if (latest == null
                    || (m.sampleTime() != null
                        && (latest.sampleTime() == null || m.sampleTime().isAfter(latest.sampleTime())))) {
                    latest = m;
                }
            }
            if (latest == null) return MetricValue.unavailable();
            return MetricValue.of(latest.value(), latest.sampleTime());
        } catch (UnsupportedOperationException e) {
            return MetricValue.unavailable();
        }
    }

    /**
     * Prefer the most recent panel marker (BloodTestReport extracts);
     * fall back to a manual BloodReading row if no panel carries the
     * marker. Both sources match the spec — panels are the canonical
     * source per Phase 0 discovery.
     */
    private MetricValue latestBloodMarker(String userId, BloodMarker marker, String panelName) {
        try {
            List<BloodTestReport> panels = bloodTestReports.findByUser(userId);
            if (panels != null) {
                // Repo contract says newest-first by sampleDate.
                for (BloodTestReport panel : panels) {
                    if (panel.markers() == null) continue;
                    for (ExtractedMarker em : panel.markers()) {
                        if (em.value() == null) continue;
                        if (em.name() == null) continue;
                        if (em.name().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "").equals(panelName)) {
                            Instant asOf = panel.sampleDate() != null
                                ? panel.sampleDate().atStartOfDay().toInstant(ZoneOffset.UTC)
                                : panel.createdAt();
                            return MetricValue.of(em.value(), asOf);
                        }
                    }
                }
            }
        } catch (UnsupportedOperationException ignored) {
            // Fall through to BloodReading lookup.
        }

        try {
            List<BloodReading> readings = bloodReadings.findByUser(userId);
            BloodReading latest = null;
            for (BloodReading r : readings) {
                if (r.marker() != marker) continue;
                if (latest == null
                    || (r.sampleDate() != null
                        && (latest.sampleDate() == null || r.sampleDate().isAfter(latest.sampleDate())))) {
                    latest = r;
                }
            }
            if (latest == null) return MetricValue.unavailable();
            Instant asOf = latest.sampleDate() != null
                ? latest.sampleDate().atStartOfDay().toInstant(ZoneOffset.UTC)
                : latest.createdAt();
            return MetricValue.of(latest.value(), asOf);
        } catch (UnsupportedOperationException e) {
            return MetricValue.unavailable();
        }
    }

    /**
     * Query a small recent window of DailyMetrics and pick the most
     * recent row whose extracted field is non-null. The persistence
     * impl is a stub today; the catch keeps us from crashing.
     */
    private MetricValue latestDailyMetric(String userId, FieldExtractor extract) {
        try {
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(7);
            List<DailyMetric> rows = dailyMetrics.findByDateRange(userId, from, to);
            if (rows == null || rows.isEmpty()) return MetricValue.unavailable();
            DailyMetric chosen = null;
            Double chosenValue = null;
            for (DailyMetric m : rows) {
                Double v = extract.extract(m);
                if (v == null) continue;
                if (chosen == null || (m.date() != null
                        && (chosen.date() == null || m.date().isAfter(chosen.date())))) {
                    chosen = m;
                    chosenValue = v;
                }
            }
            if (chosen == null || chosenValue == null) return MetricValue.unavailable();
            Instant asOf = chosen.date() != null
                ? chosen.date().atStartOfDay().toInstant(ZoneOffset.UTC)
                : chosen.updatedAt();
            return MetricValue.of(chosenValue, asOf);
        } catch (UnsupportedOperationException e) {
            return MetricValue.unavailable();
        }
    }

    private MetricValue latestWeeklyVolume(String userId) {
        try {
            LocalDate today = LocalDate.now();
            // Roll back ~8 weeks to cover any clock skew / partial weeks.
            List<WeeklyWorkoutAggregate> recent =
                weeklyAggregates.findByDateRange(userId, today.minusWeeks(8), today);
            if (recent == null || recent.isEmpty()) return MetricValue.unavailable();
            WeeklyWorkoutAggregate latest = null;
            for (WeeklyWorkoutAggregate a : recent) {
                if (a.totalTonnage() == null) continue;
                if (latest == null
                    || (a.weekStart() != null
                        && (latest.weekStart() == null || a.weekStart().isAfter(latest.weekStart())))) {
                    latest = a;
                }
            }
            if (latest == null || latest.totalTonnage() == null) return MetricValue.unavailable();
            Instant asOf = latest.weekStart() != null
                ? latest.weekStart().atStartOfDay().toInstant(ZoneOffset.UTC)
                : latest.updatedAt();
            return MetricValue.of(latest.totalTonnage(), asOf);
        } catch (UnsupportedOperationException e) {
            return MetricValue.unavailable();
        }
    }

    /**
     * 7-day trailing average of a nutrition field over the inclusive
     * window [today-6, today]. Days where {@code extract} yields null
     * are skipped (not counted as zero). Returns
     * {@link MetricValue#unavailable()} when no log in the window
     * carries a value for the field — the same convention all the
     * nutrition macros share.
     */
    private MetricValue nutrition7dAverage(String userId, NutritionFieldExtractor extract) {
        try {
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(6);  // inclusive 7-day window
            List<NutritionDailyLog> logs = nutrition.findByDateRange(userId, from, to);
            if (logs == null || logs.isEmpty()) return MetricValue.unavailable();
            double sum = 0;
            int count = 0;
            for (NutritionDailyLog log : logs) {
                Double v = extract.extract(log);
                if (v == null) continue;
                sum += v;
                count++;
            }
            if (count == 0) return MetricValue.unavailable();
            Instant asOf = to.atStartOfDay().toInstant(ZoneOffset.UTC);
            return MetricValue.of(sum / count, asOf);
        } catch (UnsupportedOperationException e) {
            return MetricValue.unavailable();
        }
    }

    /**
     * Calories for a single day: prefer the logged {@code caloriesKcal}
     * when present, otherwise derive it from macros using Atwater
     * factors (4 kcal/g protein, 4 kcal/g carbs, 9 kcal/g fat). Returns
     * null only when neither calories nor any macro is recorded, so the
     * day is skipped by {@link #nutrition7dAverage}.
     */
    private static Double caloriesForDay(NutritionDailyLog log) {
        if (log.caloriesKcal() != null) return log.caloriesKcal();
        Double protein = log.proteinGrams();
        Double carbs = log.carbsGrams();
        Double fat = log.fatGrams();
        if (protein == null && carbs == null && fat == null) return null;
        double kcal = 0;
        if (protein != null) kcal += 4 * protein;
        if (carbs != null) kcal += 4 * carbs;
        if (fat != null) kcal += 9 * fat;
        return kcal;
    }

    /**
     * COUNT of days in the inclusive window {@code [from, today]} whose logged
     * daily totals met the user's active macro target.
     *
     * <p><strong>"Day met target" rule:</strong> the user's active
     * {@link MacroTarget} is resolved once. For every macro the target sets to a
     * positive value (calories, protein, carbs, fat, fiber, sugar), that day's
     * consumed value must land within {@value #TARGET_MET_TOLERANCE} (±10%) of the
     * target. Macros the target leaves null/zero are not constrained. A day with
     * no logged totals at all does not count, and if there is no active target the
     * count is 0. Calories fall back to the Atwater-derived value
     * ({@link #caloriesForDay}) when not logged directly.
     */
    private long targetMetDaysSince(String userId, Instant from) {
        try {
            Optional<MacroTarget> active = macroTargets.findActive(userId);
            if (active.isEmpty() || active.get().macros() == null) return 0L;
            Macros target = active.get().macros();

            LocalDate today = LocalDate.now();
            LocalDate fromDate = from.atZone(ZoneOffset.UTC).toLocalDate();
            if (fromDate.isAfter(today)) return 0L;

            List<NutritionDailyLog> logs = nutrition.findByDateRange(userId, fromDate, today);
            if (logs == null || logs.isEmpty()) return 0L;

            long met = 0;
            for (NutritionDailyLog log : logs) {
                if (dayMetTarget(log, target)) met++;
            }
            return met;
        } catch (UnsupportedOperationException e) {
            return 0L;
        }
    }

    /**
     * True when every positively-targeted macro is within ±10% of the target for
     * this day. A day with no logged totals (every consumed macro null) never
     * counts.
     */
    private static boolean dayMetTarget(NutritionDailyLog log, Macros target) {
        boolean anyConsumed = log.proteinGrams() != null || log.carbsGrams() != null
            || log.fatGrams() != null || log.fiberGrams() != null
            || log.sugarGrams() != null || log.caloriesKcal() != null;
        if (!anyConsumed) return false;

        return withinTolerance(caloriesForDay(log), target.caloriesKcal())
            && withinTolerance(log.proteinGrams(), target.proteinGrams())
            && withinTolerance(log.carbsGrams(), target.carbsGrams())
            && withinTolerance(log.fatGrams(), target.fatGrams())
            && withinTolerance(log.fiberGrams(), target.fiberGrams())
            && withinTolerance(log.sugarGrams(), target.sugarGrams());
    }

    /**
     * Whether {@code consumed} is within ±10% of {@code target}. A non-positive
     * (null or {@code <= 0}) target means the macro is unconstrained and always
     * passes; a positive target with a null consumed value fails.
     */
    private static boolean withinTolerance(Double consumed, Double target) {
        if (target == null || target <= 0) return true;
        if (consumed == null) return false;
        double low = target * (1 - TARGET_MET_TOLERANCE);
        double high = target * (1 + TARGET_MET_TOLERANCE);
        return consumed >= low && consumed <= high;
    }

    /**
     * 30-day adherence percentage: doses taken / doses expected over
     * the last 30 days, across all the user's medications. We don't
     * have a "scheduled doses" projection today, so this implementation
     * counts logged doses as a proxy — adherence "above 0" means the
     * user has been tracking. A real expected-vs-actual calc lands with
     * the medication scheduling spec.
     */
    private MetricValue adherence30d(String userId) {
        try {
            LocalDate to = LocalDate.now();
            LocalDate from = to.minusDays(29);
            List<AdherenceLog> logs = adherence.findByUserAndDateRange(userId, from, to);
            if (logs == null || logs.isEmpty()) return MetricValue.unavailable();
            long takenDoses = 0;
            for (AdherenceLog log : logs) {
                if (log.doses() == null) continue;
                for (DoseLog d : log.doses()) {
                    if (d != null && d.takenAt() != null) takenDoses++;
                }
            }
            // TODO(IMPL-12): once medication scheduling lands, divide by
            // expected dose count for a real percentage. For now: doses
            // logged across the window, scaled so "100" means a dose a
            // day. This keeps comparators meaningful (e.g. >= 80).
            double pct = (takenDoses / 30.0) * 100.0;
            Instant asOf = to.atStartOfDay().toInstant(ZoneOffset.UTC);
            return MetricValue.of(pct, asOf);
        } catch (UnsupportedOperationException e) {
            return MetricValue.unavailable();
        }
    }

    @FunctionalInterface
    private interface FieldExtractor {
        Double extract(DailyMetric m);
    }

    @FunctionalInterface
    private interface NutritionFieldExtractor {
        Double extract(NutritionDailyLog log);
    }
}
