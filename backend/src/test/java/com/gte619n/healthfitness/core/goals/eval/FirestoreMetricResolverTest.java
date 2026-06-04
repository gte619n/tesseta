package com.gte619n.healthfitness.core.goals.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.nutrition.MacroTarget;
import com.gte619n.healthfitness.core.nutrition.MacroTargetRepository;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLog;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLogRepository;
import com.gte619n.healthfitness.core.workout.Workout;
import com.gte619n.healthfitness.core.workout.WorkoutRepository;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregate;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregateRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises one branch per {@link MetricKey} on {@link FirestoreMetricResolver}.
 * The aim is to guard against NPEs in the metric→repo plumbing and to
 * verify that stub-backed metrics surface {@link MetricValue#unavailable()}
 * instead of crashing.
 */
class FirestoreMetricResolverTest {

    private static final String USER = "u-1";

    @Test
    void bodyWeight_resolvesLatestMeasurement() {
        InMemBodyComposition body = new InMemBodyComposition();
        body.add(measurement(BodyCompositionMetric.WEIGHT_KG, 90.0, Instant.parse("2026-01-01T00:00:00Z")));
        body.add(measurement(BodyCompositionMetric.WEIGHT_KG, 85.5, Instant.parse("2026-04-01T00:00:00Z")));
        body.add(measurement(BodyCompositionMetric.BODY_FAT_PERCENT, 20.0, Instant.parse("2026-04-01T00:00:00Z")));

        FirestoreMetricResolver r = newResolver(body, new EmptyBloodReadings(), new EmptyBloodTestReports(),
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            new EmptyWeeklyAggregates(), new EmptyNutrition(), new EmptyAdherence());

        MetricValue v = r.resolve(USER, MetricKey.BODY_WEIGHT);
        assertTrue(v.isAvailable());
        assertEquals(85.5, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void bodyBodyFatPct_resolvesLatestPctMeasurement() {
        InMemBodyComposition body = new InMemBodyComposition();
        body.add(measurement(BodyCompositionMetric.BODY_FAT_PERCENT, 18.2, Instant.parse("2026-04-01T00:00:00Z")));
        FirestoreMetricResolver r = newResolver(body);
        assertEquals(18.2, r.resolve(USER, MetricKey.BODY_BODY_FAT_PCT).value().orElseThrow(), 1e-9);
    }

    @Test
    void bodyLeanMass_resolvesLatestLeanMassMeasurement() {
        InMemBodyComposition body = new InMemBodyComposition();
        body.add(measurement(BodyCompositionMetric.LEAN_MASS_KG, 62.0, Instant.parse("2026-04-01T00:00:00Z")));
        FirestoreMetricResolver r = newResolver(body);
        assertEquals(62.0, r.resolve(USER, MetricKey.BODY_LEAN_MASS).value().orElseThrow(), 1e-9);
    }

    @Test
    void bloodLdl_prefersMostRecentPanelMarker() {
        InMemBloodTestReports panels = new InMemBloodTestReports();
        panels.add(new BloodTestReport(
            USER, "r2", LocalDate.parse("2026-04-15"), "Quest", "/q.pdf", "hash2",
            List.of(new ExtractedMarker("LDL", 87.0, "mg/dL", null, null, null)),
            Instant.now(), Instant.now()
        ));
        panels.add(new BloodTestReport(
            USER, "r1", LocalDate.parse("2025-12-01"), "Quest", "/q1.pdf", "hash1",
            List.of(new ExtractedMarker("LDL", 130.0, "mg/dL", null, null, null)),
            Instant.now(), Instant.now()
        ));
        FirestoreMetricResolver r = newResolverWithBlood(panels);

        MetricValue v = r.resolve(USER, MetricKey.BLOOD_LDL);
        assertTrue(v.isAvailable());
        assertEquals(87.0, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void bloodApoB_resolvesFromPanel() {
        InMemBloodTestReports panels = new InMemBloodTestReports();
        panels.add(new BloodTestReport(
            USER, "r1", LocalDate.parse("2026-04-15"), "Quest", "/q.pdf", "h",
            List.of(new ExtractedMarker("APOB", 70.0, "mg/dL", null, null, null)),
            Instant.now(), Instant.now()
        ));
        FirestoreMetricResolver r = newResolverWithBlood(panels);
        assertEquals(70.0, r.resolve(USER, MetricKey.BLOOD_APOB).value().orElseThrow(), 1e-9);
    }

    @Test
    void bloodHba1c_fallsBackToBloodReadings_whenPanelEmpty() {
        InMemBloodReadings readings = new InMemBloodReadings();
        readings.add(new BloodReading(
            USER, "br1", BloodMarker.HBA1C, 5.4, "%",
            LocalDate.parse("2026-04-10"), "labcorp", null,
            Instant.now(), Instant.now()
        ));
        FirestoreMetricResolver r = newResolver(
            new EmptyBodyComposition(), readings, new EmptyBloodTestReports(),
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            new EmptyWeeklyAggregates(), new EmptyNutrition(), new EmptyAdherence()
        );
        assertEquals(5.4, r.resolve(USER, MetricKey.BLOOD_HBA1C).value().orElseThrow(), 1e-9);
    }

    @Test
    void bloodHsCrp_resolves() {
        InMemBloodTestReports panels = new InMemBloodTestReports();
        panels.add(new BloodTestReport(
            USER, "r1", LocalDate.parse("2026-04-15"), "Quest", "/q.pdf", "h",
            List.of(new ExtractedMarker("HSCRP", 0.4, "mg/L", null, null, null)),
            Instant.now(), Instant.now()
        ));
        FirestoreMetricResolver r = newResolverWithBlood(panels);
        assertEquals(0.4, r.resolve(USER, MetricKey.BLOOD_HS_CRP).value().orElseThrow(), 1e-9);
    }

    @Test
    void vitalsRestingHr_surfacesUnavailable_whenDailyMetricStubThrows() {
        FirestoreMetricResolver r = newResolver();
        // DailyMetricRepository stub throws — resolver must catch and degrade.
        MetricValue v = r.resolve(USER, MetricKey.VITALS_RESTING_HR);
        assertFalse(v.isAvailable());
    }

    @Test
    void vitalsHrv_surfacesUnavailable_whenDailyMetricStubThrows() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.VITALS_HRV).isAvailable());
    }

    @Test
    void vitalsSleepScore_surfacesUnavailable_whenDailyMetricStubThrows() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.VITALS_SLEEP_SCORE).isAvailable());
    }

    @Test
    void workoutsCount_isAlwaysUnavailable_inResolve() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.WORKOUTS_COUNT).isAvailable(),
            "WORKOUTS_COUNT is window-dependent — resolve() must surface unavailable");
    }

    @Test
    void workoutsCount_countSince_runsAgainstWorkoutRepo() {
        InMemWorkouts workouts = new InMemWorkouts();
        Instant base = Instant.parse("2026-05-01T00:00:00Z");
        workouts.add(new Workout(USER, "w1", "RUN", null, base, base.plusSeconds(60),
            "health-connect", base, base));
        workouts.add(new Workout(USER, "w2", "BIKE", null, base.plusSeconds(86_400), base.plusSeconds(86_460),
            "health-connect", base, base));

        FirestoreMetricResolver r = newResolver(
            new EmptyBodyComposition(), new EmptyBloodReadings(), new EmptyBloodTestReports(),
            new ThrowingDailyMetrics(), workouts,
            new EmptyWeeklyAggregates(), new EmptyNutrition(), new EmptyAdherence()
        );

        long count = r.countSince(USER, MetricKey.WORKOUTS_COUNT, base);
        assertEquals(2L, count);
    }

    @Test
    void workoutsCount_countSince_returnsZero_whenWorkoutRepoStubThrows() {
        FirestoreMetricResolver r = newResolver();
        long count = r.countSince(USER, MetricKey.WORKOUTS_COUNT, Instant.now().minusSeconds(3600));
        assertEquals(0L, count, "stub-backed workouts repo degrades to 0");
    }

    @Test
    void workoutsWeeklyVolume_resolvesLatestAggregate() {
        InMemWeeklyAggregates agg = new InMemWeeklyAggregates();
        agg.add(new WeeklyWorkoutAggregate(USER, LocalDate.now().minusWeeks(2),
            12000.0, 4, Instant.now(), Instant.now()));
        agg.add(new WeeklyWorkoutAggregate(USER, LocalDate.now().minusWeeks(1),
            13500.0, 4, Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolver(
            new EmptyBodyComposition(), new EmptyBloodReadings(), new EmptyBloodTestReports(),
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            agg, new EmptyNutrition(), new EmptyAdherence()
        );
        MetricValue v = r.resolve(USER, MetricKey.WORKOUTS_WEEKLY_VOLUME);
        assertTrue(v.isAvailable());
        assertEquals(13500.0, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void workoutsWeeklyVolume_unavailable_whenNoAggregates() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.WORKOUTS_WEEKLY_VOLUME).isAvailable());
    }

    @Test
    void nutritionProteinAvg7d_returnsAverage_overWindow() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(2),
            100.0, null, null, null, null, null, Instant.now(), Instant.now()));
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(1),
            120.0, null, null, null, null, null, Instant.now(), Instant.now()));
        nutrition.add(new NutritionDailyLog(USER, today,
            140.0, null, null, null, null, null, Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolver(
            new EmptyBodyComposition(), new EmptyBloodReadings(), new EmptyBloodTestReports(),
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            new EmptyWeeklyAggregates(), nutrition, new EmptyAdherence()
        );
        MetricValue v = r.resolve(USER, MetricKey.NUTRITION_PROTEIN_AVG_7D);
        assertTrue(v.isAvailable());
        assertEquals(120.0, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void nutritionProteinAvg7d_unavailable_whenStubEmpty() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.NUTRITION_PROTEIN_AVG_7D).isAvailable());
    }

    @Test
    void nutritionCarbsAvg7d_returnsAverage_overWindow() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(1),
            null, 200.0, null, null, null, null, Instant.now(), Instant.now()));
        nutrition.add(new NutritionDailyLog(USER, today,
            null, 300.0, null, null, null, null, Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolverWithNutrition(nutrition);
        MetricValue v = r.resolve(USER, MetricKey.NUTRITION_CARBS_AVG_7D);
        assertTrue(v.isAvailable());
        assertEquals(250.0, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void nutritionFatAvg7d_returnsAverage_overWindow() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(1),
            null, null, 60.0, null, null, null, Instant.now(), Instant.now()));
        nutrition.add(new NutritionDailyLog(USER, today,
            null, null, 80.0, null, null, null, Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolverWithNutrition(nutrition);
        MetricValue v = r.resolve(USER, MetricKey.NUTRITION_FAT_AVG_7D);
        assertTrue(v.isAvailable());
        assertEquals(70.0, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void nutritionCaloriesAvg7d_prefersLoggedCalories() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(1),
            100.0, 200.0, 50.0, null, null, 2000.0, Instant.now(), Instant.now()));
        nutrition.add(new NutritionDailyLog(USER, today,
            100.0, 200.0, 50.0, null, null, 2400.0, Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolverWithNutrition(nutrition);
        MetricValue v = r.resolve(USER, MetricKey.NUTRITION_CALORIES_AVG_7D);
        assertTrue(v.isAvailable());
        // Logged calories used verbatim: (2000 + 2400) / 2 = 2200.
        assertEquals(2200.0, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void nutritionCaloriesAvg7d_derivesFromMacros_viaAtwater_whenNoCalories() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        // 4*100 + 4*200 + 9*50 = 400 + 800 + 450 = 1650 kcal each day.
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(1),
            100.0, 200.0, 50.0, null, null, null, Instant.now(), Instant.now()));
        nutrition.add(new NutritionDailyLog(USER, today,
            100.0, 200.0, 50.0, null, null, null, Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolverWithNutrition(nutrition);
        MetricValue v = r.resolve(USER, MetricKey.NUTRITION_CALORIES_AVG_7D);
        assertTrue(v.isAvailable());
        assertEquals(1650.0, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void nutritionCarbsAvg7d_unavailable_whenNoLogs() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.NUTRITION_CARBS_AVG_7D).isAvailable());
    }

    @Test
    void nutritionFatAvg7d_unavailable_whenNoLogs() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.NUTRITION_FAT_AVG_7D).isAvailable());
    }

    @Test
    void nutritionCaloriesAvg7d_unavailable_whenNoLogs() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.NUTRITION_CALORIES_AVG_7D).isAvailable());
    }

    @Test
    void nutritionFiberAvg7d_returnsAverage_overWindow() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(1),
            null, null, null, 20.0, null, null, Instant.now(), Instant.now()));
        nutrition.add(new NutritionDailyLog(USER, today,
            null, null, null, 30.0, null, null, Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolverWithNutrition(nutrition);
        MetricValue v = r.resolve(USER, MetricKey.NUTRITION_FIBER_AVG_7D);
        assertTrue(v.isAvailable());
        assertEquals(25.0, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void nutritionFiberAvg7d_unavailable_whenNoLogs() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.NUTRITION_FIBER_AVG_7D).isAvailable());
    }

    @Test
    void nutritionSugarAvg7d_returnsAverage_overWindow() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(1),
            null, null, null, null, 40.0, null, Instant.now(), Instant.now()));
        nutrition.add(new NutritionDailyLog(USER, today,
            null, null, null, null, 60.0, null, Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolverWithNutrition(nutrition);
        MetricValue v = r.resolve(USER, MetricKey.NUTRITION_SUGAR_AVG_7D);
        assertTrue(v.isAvailable());
        assertEquals(50.0, v.value().orElseThrow(), 1e-9);
    }

    @Test
    void nutritionSugarAvg7d_unavailable_whenNoLogs() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.NUTRITION_SUGAR_AVG_7D).isAvailable());
    }

    @Test
    void nutritionTargetMetDays_isAlwaysUnavailable_inResolve() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.NUTRITION_TARGET_MET_DAYS).isAvailable(),
            "NUTRITION_TARGET_MET_DAYS is window-dependent — resolve() must surface unavailable");
    }

    @Test
    void nutritionTargetMetDays_countsDaysWithinTolerance() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        // Target: 2000 kcal, 150 protein, 200 carbs, 60 fat, 30 fiber, 50 sugar.
        // Day -2: every macro exactly on target -> counts.
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(2),
            150.0, 200.0, 60.0, 30.0, 50.0, 2000.0, Instant.now(), Instant.now()));
        // Day -1: protein within +9% (163.5 <= 165), others on target -> counts.
        nutrition.add(new NutritionDailyLog(USER, today.minusDays(1),
            163.5, 200.0, 60.0, 30.0, 50.0, 2000.0, Instant.now(), Instant.now()));
        // Day 0: protein 100 (well below 135 floor) -> does NOT count.
        nutrition.add(new NutritionDailyLog(USER, today,
            100.0, 200.0, 60.0, 30.0, 50.0, 2000.0, Instant.now(), Instant.now()));

        InMemMacroTargets targets = new InMemMacroTargets();
        targets.add(new MacroTarget(USER, "t1",
            new Macros(2000.0, 150.0, 200.0, 60.0, 30.0, 50.0),
            today.minusDays(30), Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolverWithNutritionAndTarget(nutrition, targets);
        long count = r.countSince(USER, MetricKey.NUTRITION_TARGET_MET_DAYS,
            today.minusDays(2).atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        assertEquals(2L, count);
    }

    @Test
    void nutritionTargetMetDays_zero_whenNoActiveTarget() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        nutrition.add(new NutritionDailyLog(USER, today,
            150.0, 200.0, 60.0, 30.0, 50.0, 2000.0, Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolverWithNutritionAndTarget(nutrition, new EmptyMacroTargets());
        long count = r.countSince(USER, MetricKey.NUTRITION_TARGET_MET_DAYS,
            today.minusDays(6).atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        assertEquals(0L, count);
    }

    @Test
    void nutritionTargetMetDays_doesNotCountDayWithNoLoggedTotals() {
        InMemNutrition nutrition = new InMemNutrition();
        LocalDate today = LocalDate.now();
        // A day row exists but every consumed macro is null -> not counted.
        nutrition.add(new NutritionDailyLog(USER, today,
            null, null, null, null, null, null, Instant.now(), Instant.now()));

        InMemMacroTargets targets = new InMemMacroTargets();
        targets.add(new MacroTarget(USER, "t1",
            new Macros(2000.0, 150.0, 200.0, 60.0, 30.0, 50.0),
            today.minusDays(30), Instant.now(), Instant.now()));

        FirestoreMetricResolver r = newResolverWithNutritionAndTarget(nutrition, targets);
        long count = r.countSince(USER, MetricKey.NUTRITION_TARGET_MET_DAYS,
            today.minusDays(6).atStartOfDay().toInstant(java.time.ZoneOffset.UTC));
        assertEquals(0L, count);
    }

    @Test
    void medsAdherence30d_computesPercentage_fromLoggedDoses() {
        InMemAdherence adherence = new InMemAdherence();
        LocalDate today = LocalDate.now();
        for (int i = 0; i < 10; i++) {
            adherence.add(new AdherenceLog(
                USER, "m1", today.minusDays(i),
                List.of(new DoseLog(TimeWindow.MORNING, Instant.now(), 100.0)),
                null
            ));
        }
        FirestoreMetricResolver r = newResolver(
            new EmptyBodyComposition(), new EmptyBloodReadings(), new EmptyBloodTestReports(),
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            new EmptyWeeklyAggregates(), new EmptyNutrition(), adherence
        );
        MetricValue v = r.resolve(USER, MetricKey.MEDS_ADHERENCE_30D);
        assertTrue(v.isAvailable());
        // 10 doses / 30 days * 100 = ~33.3%
        assertEquals(33.333, v.value().orElseThrow(), 0.1);
    }

    @Test
    void medsAdherence30d_unavailable_whenNoLogs() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, MetricKey.MEDS_ADHERENCE_30D).isAvailable());
    }

    @Test
    void resolve_nullKey_returnsUnavailable() {
        FirestoreMetricResolver r = newResolver();
        assertFalse(r.resolve(USER, null).isAvailable());
    }

    // ---------- helpers ----------

    private FirestoreMetricResolver newResolver() {
        return newResolver(
            new EmptyBodyComposition(), new EmptyBloodReadings(), new EmptyBloodTestReports(),
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            new EmptyWeeklyAggregates(), new EmptyNutrition(), new EmptyAdherence()
        );
    }

    private FirestoreMetricResolver newResolver(BodyCompositionRepository body) {
        return newResolver(body,
            new EmptyBloodReadings(), new EmptyBloodTestReports(),
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            new EmptyWeeklyAggregates(), new EmptyNutrition(), new EmptyAdherence()
        );
    }

    private FirestoreMetricResolver newResolverWithNutrition(NutritionDailyLogRepository nutrition) {
        return newResolver(
            new EmptyBodyComposition(), new EmptyBloodReadings(), new EmptyBloodTestReports(),
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            new EmptyWeeklyAggregates(), nutrition, new EmptyAdherence()
        );
    }

    private FirestoreMetricResolver newResolverWithBlood(InMemBloodTestReports panels) {
        return newResolver(
            new EmptyBodyComposition(), new EmptyBloodReadings(), panels,
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            new EmptyWeeklyAggregates(), new EmptyNutrition(), new EmptyAdherence()
        );
    }

    private FirestoreMetricResolver newResolver(
        BodyCompositionRepository body,
        BloodReadingRepository bloodReadings,
        BloodTestReportRepository bloodTestReports,
        DailyMetricRepository dailyMetrics,
        WorkoutRepository workouts,
        WeeklyWorkoutAggregateRepository weeklyAggregates,
        NutritionDailyLogRepository nutrition,
        AdherenceRepository adherence
    ) {
        return new FirestoreMetricResolver(
            bloodReadings, bloodTestReports, body, dailyMetrics, workouts,
            weeklyAggregates, nutrition, new EmptyMacroTargets(), adherence
        );
    }

    private FirestoreMetricResolver newResolverWithNutritionAndTarget(
        NutritionDailyLogRepository nutrition,
        MacroTargetRepository macroTargets
    ) {
        return new FirestoreMetricResolver(
            new EmptyBloodReadings(), new EmptyBloodTestReports(), new EmptyBodyComposition(),
            new ThrowingDailyMetrics(), new ThrowingWorkouts(),
            new EmptyWeeklyAggregates(), nutrition, macroTargets, new EmptyAdherence()
        );
    }

    private static BodyCompositionMeasurement measurement(BodyCompositionMetric metric, double value, Instant time) {
        return new BodyCompositionMeasurement(USER, "rec-" + time, metric, value, time, null, null, time, time);
    }

    // ---------- in-memory test doubles ----------

    private static final class InMemBodyComposition implements BodyCompositionRepository {
        private final List<BodyCompositionMeasurement> rows = new ArrayList<>();
        void add(BodyCompositionMeasurement m) { rows.add(m); }
        @Override public Optional<BodyCompositionMeasurement> findById(String userId, String recordId) {
            for (BodyCompositionMeasurement m : rows) if (m.recordId().equals(recordId)) return Optional.of(m);
            return Optional.empty();
        }
        @Override public List<BodyCompositionMeasurement> findByUserAndRange(String userId, BodyCompositionMetric metric, Instant from, Instant to) {
            return List.of();
        }
        @Override public List<BodyCompositionMeasurement> findByUser(String userId) {
            return Collections.unmodifiableList(rows);
        }
        @Override public void save(BodyCompositionMeasurement measurement) { rows.add(measurement); }
        @Override public void saveAll(List<BodyCompositionMeasurement> ms) { rows.addAll(ms); }
        @Override public void delete(String userId, String recordId) {}
        @Override public void deleteByUserMetricAndRange(String userId, BodyCompositionMetric metric, Instant from, Instant to) {}
    }

    private static final class EmptyBodyComposition implements BodyCompositionRepository {
        @Override public Optional<BodyCompositionMeasurement> findById(String u, String r) { return Optional.empty(); }
        @Override public List<BodyCompositionMeasurement> findByUserAndRange(String u, BodyCompositionMetric m, Instant f, Instant t) { return List.of(); }
        @Override public List<BodyCompositionMeasurement> findByUser(String u) { return List.of(); }
        @Override public void save(BodyCompositionMeasurement m) {}
        @Override public void saveAll(List<BodyCompositionMeasurement> ms) {}
        @Override public void delete(String u, String r) {}
        @Override public void deleteByUserMetricAndRange(String u, BodyCompositionMetric m, Instant f, Instant t) {}
    }

    private static final class InMemBloodReadings implements BloodReadingRepository {
        private final List<BloodReading> rows = new ArrayList<>();
        void add(BloodReading r) { rows.add(r); }
        @Override public Optional<BloodReading> findById(String u, String id) {
            for (BloodReading r : rows) if (r.readingId().equals(id)) return Optional.of(r);
            return Optional.empty();
        }
        @Override public List<BloodReading> findByUser(String u) { return Collections.unmodifiableList(rows); }
        @Override public void save(BloodReading r) { rows.add(r); }
        @Override public void delete(String u, String id) {}
    }

    private static final class EmptyBloodReadings implements BloodReadingRepository {
        @Override public Optional<BloodReading> findById(String u, String id) { return Optional.empty(); }
        @Override public List<BloodReading> findByUser(String u) { return List.of(); }
        @Override public void save(BloodReading r) {}
        @Override public void delete(String u, String id) {}
    }

    private static final class InMemBloodTestReports implements BloodTestReportRepository {
        private final List<BloodTestReport> rows = new ArrayList<>();
        void add(BloodTestReport r) { rows.add(r); }
        @Override public void save(BloodTestReport r) { rows.add(r); }
        @Override public Optional<BloodTestReport> findById(String u, String id) {
            for (BloodTestReport r : rows) if (r.reportId().equals(id)) return Optional.of(r);
            return Optional.empty();
        }
        // Contract: newest-first by sampleDate. Test data is added newest-first so we just return as-is.
        @Override public List<BloodTestReport> findByUser(String u) { return Collections.unmodifiableList(rows); }
        @Override public Optional<BloodTestReport> findByContentHash(String u, String hash) { return Optional.empty(); }
        @Override public void delete(String u, String id) {}
    }

    private static final class EmptyBloodTestReports implements BloodTestReportRepository {
        @Override public void save(BloodTestReport r) {}
        @Override public Optional<BloodTestReport> findById(String u, String id) { return Optional.empty(); }
        @Override public List<BloodTestReport> findByUser(String u) { return List.of(); }
        @Override public Optional<BloodTestReport> findByContentHash(String u, String h) { return Optional.empty(); }
        @Override public void delete(String u, String id) {}
    }

    private static final class ThrowingDailyMetrics implements DailyMetricRepository {
        @Override public Optional<DailyMetric> findByDate(String u, LocalDate d) {
            throw new UnsupportedOperationException("stub");
        }
        @Override public List<DailyMetric> findByDateRange(String u, LocalDate f, LocalDate t) {
            throw new UnsupportedOperationException("stub");
        }
        @Override public void save(DailyMetric m) {
            throw new UnsupportedOperationException("stub");
        }
    }

    private static final class InMemWorkouts implements WorkoutRepository {
        private final List<Workout> rows = new ArrayList<>();
        void add(Workout w) { rows.add(w); }
        @Override public Optional<Workout> findById(String u, String id) {
            for (Workout w : rows) if (w.workoutId().equals(id)) return Optional.of(w);
            return Optional.empty();
        }
        @Override public List<Workout> findByUser(String u) { return Collections.unmodifiableList(rows); }
        @Override public void save(Workout w) { rows.add(w); }
    }

    private static final class ThrowingWorkouts implements WorkoutRepository {
        @Override public Optional<Workout> findById(String u, String id) { throw new UnsupportedOperationException("stub"); }
        @Override public List<Workout> findByUser(String u) { throw new UnsupportedOperationException("stub"); }
        @Override public void save(Workout w) { throw new UnsupportedOperationException("stub"); }
    }

    private static final class InMemWeeklyAggregates implements WeeklyWorkoutAggregateRepository {
        private final List<WeeklyWorkoutAggregate> rows = new ArrayList<>();
        void add(WeeklyWorkoutAggregate a) { rows.add(a); }
        @Override public Optional<WeeklyWorkoutAggregate> findByWeekStart(String u, LocalDate w) {
            for (WeeklyWorkoutAggregate a : rows) if (a.weekStart().equals(w)) return Optional.of(a);
            return Optional.empty();
        }
        @Override public List<WeeklyWorkoutAggregate> findByDateRange(String u, LocalDate f, LocalDate t) {
            List<WeeklyWorkoutAggregate> out = new ArrayList<>();
            for (WeeklyWorkoutAggregate a : rows) {
                if (!a.weekStart().isBefore(f) && !a.weekStart().isAfter(t)) out.add(a);
            }
            return out;
        }
        @Override public void save(WeeklyWorkoutAggregate a) { rows.add(a); }
    }

    private static final class EmptyWeeklyAggregates implements WeeklyWorkoutAggregateRepository {
        @Override public Optional<WeeklyWorkoutAggregate> findByWeekStart(String u, LocalDate w) { return Optional.empty(); }
        @Override public List<WeeklyWorkoutAggregate> findByDateRange(String u, LocalDate f, LocalDate t) { return List.of(); }
        @Override public void save(WeeklyWorkoutAggregate a) {}
    }

    private static final class InMemNutrition implements NutritionDailyLogRepository {
        private final Map<LocalDate, NutritionDailyLog> rows = new HashMap<>();
        void add(NutritionDailyLog log) { rows.put(log.date(), log); }
        @Override public Optional<NutritionDailyLog> findByDate(String u, LocalDate d) { return Optional.ofNullable(rows.get(d)); }
        @Override public List<NutritionDailyLog> findByDateRange(String u, LocalDate f, LocalDate t) {
            List<NutritionDailyLog> out = new ArrayList<>();
            for (NutritionDailyLog log : rows.values()) {
                if (!log.date().isBefore(f) && !log.date().isAfter(t)) out.add(log);
            }
            return out;
        }
        @Override public void save(NutritionDailyLog log) { rows.put(log.date(), log); }
    }

    private static final class EmptyNutrition implements NutritionDailyLogRepository {
        @Override public Optional<NutritionDailyLog> findByDate(String u, LocalDate d) { return Optional.empty(); }
        @Override public List<NutritionDailyLog> findByDateRange(String u, LocalDate f, LocalDate t) { return List.of(); }
        @Override public void save(NutritionDailyLog log) {}
    }

    private static final class InMemAdherence implements AdherenceRepository {
        private final List<AdherenceLog> rows = new ArrayList<>();
        void add(AdherenceLog log) { rows.add(log); }
        @Override public Optional<AdherenceLog> findByDate(String u, String m, LocalDate d) {
            for (AdherenceLog l : rows) if (l.medicationId().equals(m) && l.date().equals(d)) return Optional.of(l);
            return Optional.empty();
        }
        @Override public List<AdherenceLog> findByDateRange(String u, String m, LocalDate f, LocalDate t) { return List.of(); }
        @Override public List<AdherenceLog> findByUserAndDateRange(String u, LocalDate f, LocalDate t) {
            List<AdherenceLog> out = new ArrayList<>();
            for (AdherenceLog l : rows) {
                if (!l.date().isBefore(f) && !l.date().isAfter(t)) out.add(l);
            }
            return out;
        }
        @Override public void save(AdherenceLog log) { rows.add(log); }
        @Override public void deleteByDate(String u, String m, LocalDate d) {}
    }

    private static final class InMemMacroTargets implements MacroTargetRepository {
        private final List<MacroTarget> rows = new ArrayList<>();
        void add(MacroTarget t) { rows.add(t); }
        @Override public Optional<MacroTarget> findActive(String u) {
            // Greatest effectiveFrom <= today.
            MacroTarget best = null;
            LocalDate today = LocalDate.now();
            for (MacroTarget t : rows) {
                if (t.effectiveFrom() != null && t.effectiveFrom().isAfter(today)) continue;
                if (best == null || (t.effectiveFrom() != null
                        && (best.effectiveFrom() == null || t.effectiveFrom().isAfter(best.effectiveFrom())))) {
                    best = t;
                }
            }
            return Optional.ofNullable(best);
        }
        @Override public void save(MacroTarget t) { rows.add(t); }
        @Override public List<MacroTarget> findAll(String u) { return Collections.unmodifiableList(rows); }
    }

    private static final class EmptyMacroTargets implements MacroTargetRepository {
        @Override public Optional<MacroTarget> findActive(String u) { return Optional.empty(); }
        @Override public void save(MacroTarget t) {}
        @Override public List<MacroTarget> findAll(String u) { return List.of(); }
    }

    private static final class EmptyAdherence implements AdherenceRepository {
        @Override public Optional<AdherenceLog> findByDate(String u, String m, LocalDate d) { return Optional.empty(); }
        @Override public List<AdherenceLog> findByDateRange(String u, String m, LocalDate f, LocalDate t) { return List.of(); }
        @Override public List<AdherenceLog> findByUserAndDateRange(String u, LocalDate f, LocalDate t) { return List.of(); }
        @Override public void save(AdherenceLog log) {}
        @Override public void deleteByDate(String u, String m, LocalDate d) {}
    }
}
