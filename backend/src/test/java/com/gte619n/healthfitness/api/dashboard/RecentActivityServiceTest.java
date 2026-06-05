package com.gte619n.healthfitness.api.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.medication.AdherenceLog;
import com.gte619n.healthfitness.core.medication.AdherenceRepository;
import com.gte619n.healthfitness.core.medication.DoseLog;
import com.gte619n.healthfitness.core.medication.Drug;
import com.gte619n.healthfitness.core.medication.DrugRepository;
import com.gte619n.healthfitness.core.medication.Medication;
import com.gte619n.healthfitness.core.medication.MedicationRepository;
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodEntryRepository;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MealType;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutScheduleService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test for the dashboard recent-activity aggregator: the cross-source
 * merge/sort/cap and the display-string formatting. Workouts are left empty
 * (covered by their own history tests); the four data-driven sources are
 * exercised directly with mocked repositories.
 */
class RecentActivityServiceTest {

    private WorkoutProgramService programs;
    private WorkoutScheduleService schedule;
    private BodyCompositionRepository bodyComposition;
    private DailyMetricRepository dailyMetrics;
    private FoodEntryRepository foodEntries;
    private MedicationRepository medications;
    private AdherenceRepository adherence;
    private DrugRepository drugs;
    private RecentActivityService service;

    private static final String USER = "user-1";

    @BeforeEach
    void setUp() {
        programs = mock(WorkoutProgramService.class);
        schedule = mock(WorkoutScheduleService.class);
        bodyComposition = mock(BodyCompositionRepository.class);
        dailyMetrics = mock(DailyMetricRepository.class);
        foodEntries = mock(FoodEntryRepository.class);
        medications = mock(MedicationRepository.class);
        adherence = mock(AdherenceRepository.class);
        drugs = mock(DrugRepository.class);
        service = new RecentActivityService(
            programs, schedule, bodyComposition, dailyMetrics,
            foodEntries, medications, adherence, drugs);

        // Default: every source empty. Tests fill in what they exercise.
        when(programs.list(anyString())).thenReturn(List.of());
        when(bodyComposition.findByUserAndRange(anyString(), any(), any(), any())).thenReturn(List.of());
        when(dailyMetrics.findByDateRange(anyString(), any(), any())).thenReturn(List.of());
        when(foodEntries.findByDate(anyString(), any())).thenReturn(List.of());
        when(adherence.findByUserAndDateRange(anyString(), any(), any())).thenReturn(List.of());
        when(medications.findByUser(anyString())).thenReturn(List.of());
        when(drugs.findByIds(any())).thenReturn(Map.of());
    }

    @Test
    void mergesSourcesNewestFirstAndFormatsStrings() {
        // Weigh-in: oldest of the three.
        when(bodyComposition.findByUserAndRange(eq(USER), eq(BodyCompositionMetric.WEIGHT_KG), any(), any()))
            .thenReturn(List.of(weighIn(85.8, "Aria Air", Instant.parse("2026-06-05T06:14:00Z"))));
        // Sleep: keyed off date (start-of-day UTC) — the oldest overall here.
        when(dailyMetrics.findByDateRange(eq(USER), any(), any()))
            .thenReturn(List.of(sleep(LocalDate.parse("2026-06-04"), 462, 87)));
        // Food: today, newest.
        when(foodEntries.findByDate(eq(USER), eq(LocalDate.now())))
            .thenReturn(List.of(food("Greek yogurt", MealType.BREAKFAST, 180.0,
                Instant.parse("2026-06-05T07:30:00Z"))));

        List<RecentActivityResponse> feed = service.recent(USER, 5);

        assertEquals(3, feed.size());
        // Newest first: food (07:30) > weigh-in (06:14) > sleep (start of 06-04).
        assertEquals(ActivityKind.FOOD, feed.get(0).kind());
        assertEquals("Logged Greek yogurt", feed.get(0).title());
        assertEquals("Breakfast · 180 kcal", feed.get(0).subtitle());

        assertEquals(ActivityKind.WEIGH_IN, feed.get(1).kind());
        assertEquals("Weighed in · 189.2 lb", feed.get(1).title());
        assertEquals("Aria Air", feed.get(1).subtitle());

        assertEquals(ActivityKind.SLEEP, feed.get(2).kind());
        assertEquals("Sleep · 7h 42m", feed.get(2).title());
        assertEquals("Score 87", feed.get(2).subtitle());
    }

    @Test
    void capsToLimit() {
        when(foodEntries.findByDate(eq(USER), eq(LocalDate.now()))).thenReturn(List.of(
            food("A", MealType.LUNCH, 100.0, Instant.parse("2026-06-05T12:00:00Z")),
            food("B", MealType.LUNCH, 100.0, Instant.parse("2026-06-05T12:30:00Z")),
            food("C", MealType.LUNCH, 100.0, Instant.parse("2026-06-05T13:00:00Z"))));

        List<RecentActivityResponse> feed = service.recent(USER, 2);

        assertEquals(2, feed.size());
        assertEquals("Logged C", feed.get(0).title()); // newest
        assertEquals("Logged B", feed.get(1).title());
    }

    @Test
    void resolvesMedicationDoseDisplay() {
        Medication med = mock(Medication.class);
        when(med.medicationId()).thenReturn("m1");
        when(med.customName()).thenReturn(null);
        when(med.drugId()).thenReturn("d1");
        when(med.unit()).thenReturn("mg");
        Drug drug = mock(Drug.class);
        when(drug.name()).thenReturn("Rosuvastatin");

        when(medications.findByUser(USER)).thenReturn(List.of(med));
        when(drugs.findByIds(any())).thenReturn(Map.of("d1", drug));
        when(adherence.findByUserAndDateRange(eq(USER), any(), any())).thenReturn(List.of(
            new AdherenceLog(USER, "m1", LocalDate.parse("2026-06-05"),
                List.of(new DoseLog(TimeWindow.MORNING, Instant.parse("2026-06-05T05:58:00Z"), 10.0)),
                null)));

        List<RecentActivityResponse> feed = service.recent(USER, 5);

        assertEquals(1, feed.size());
        assertEquals(ActivityKind.MEDICATION, feed.get(0).kind());
        assertEquals("Rosuvastatin · 10 mg", feed.get(0).title());
        assertNull(feed.get(0).subtitle());
    }

    @Test
    void oneFailingSourceDoesNotBreakTheFeed() {
        when(dailyMetrics.findByDateRange(anyString(), any(), any()))
            .thenThrow(new RuntimeException("firestore down"));
        when(foodEntries.findByDate(eq(USER), eq(LocalDate.now())))
            .thenReturn(List.of(food("Oatmeal", MealType.BREAKFAST, 150.0,
                Instant.parse("2026-06-05T08:00:00Z"))));

        List<RecentActivityResponse> feed = service.recent(USER, 5);

        // Sleep source threw and was swallowed; food still made it through.
        assertEquals(1, feed.size());
        assertEquals("Logged Oatmeal", feed.get(0).title());
    }

    @Test
    void skipsInFlightPhotoPlaceholders() {
        FoodEntry analyzing = mock(FoodEntry.class);
        when(analyzing.createdAt()).thenReturn(Instant.parse("2026-06-05T08:00:00Z"));
        when(analyzing.isAnalyzing()).thenReturn(true);
        when(foodEntries.findByDate(eq(USER), eq(LocalDate.now()))).thenReturn(List.of(analyzing));

        assertTrue(service.recent(USER, 5).isEmpty());
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private static BodyCompositionMeasurement weighIn(double kg, String platform, Instant at) {
        return new BodyCompositionMeasurement(
            USER, "rec", BodyCompositionMetric.WEIGHT_KG, kg, at, platform, "MANUAL", at, at);
    }

    private static DailyMetric sleep(LocalDate date, int sleepMinutes, int sleepScore) {
        return new DailyMetric(USER, date, null, null, sleepMinutes, null, sleepScore, null, null);
    }

    private static FoodEntry food(String name, MealType meal, double kcal, Instant createdAt) {
        Macros macros = new Macros(kcal, null, null, null, null, null);
        return new FoodEntry(
            USER, LocalDate.now(), "e", meal, null, name, null, null, null, macros,
            null, null, null, null, null, null, null, createdAt, createdAt);
    }
}
