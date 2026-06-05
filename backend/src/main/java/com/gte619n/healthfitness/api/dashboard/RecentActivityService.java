package com.gte619n.healthfitness.api.dashboard;

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
import com.gte619n.healthfitness.core.metric.DailyMetric;
import com.gte619n.healthfitness.core.metric.DailyMetricRepository;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodEntryRepository;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MealType;
import com.gte619n.healthfitness.core.workoutprogram.Block;
import com.gte619n.healthfitness.core.workoutprogram.Prescription;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledStatus;
import com.gte619n.healthfitness.core.workoutprogram.ScheduledWorkout;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutDay;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgram;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutProgramService;
import com.gte619n.healthfitness.core.workoutprogram.WorkoutScheduleService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Builds the dashboard "Recent" feed by merging the user's latest activity
 * across five domains — completed workouts, weigh-ins, sleep, logged food, and
 * medication doses taken — newest-first, capped to a small limit.
 *
 * <p>Each source is wrapped in {@link #safe} so a partial outage (one domain's
 * Firestore read failing) just thins the feed rather than breaking the whole
 * endpoint, mirroring the per-source degradation the clients used to do.
 *
 * <p>Lookback windows are intentionally short: a "recent" feed only ever shows
 * the top few events, so older items would never win a slot against fresher
 * activity. Food is queried day-by-day (entries are stored per-day), so its
 * window is the tightest to keep the read count low.
 */
@Service
public class RecentActivityService {

    private static final Logger log = LoggerFactory.getLogger(RecentActivityService.class);

    private static final int WORKOUT_LOOKBACK_DAYS = 30;
    private static final int VITALS_LOOKBACK_DAYS = 30;
    private static final int MED_LOOKBACK_DAYS = 7;
    private static final int FOOD_LOOKBACK_DAYS = 4;

    private static final double LB_PER_KG = 2.20462;

    private final WorkoutProgramService programs;
    private final WorkoutScheduleService schedule;
    private final BodyCompositionRepository bodyComposition;
    private final DailyMetricRepository dailyMetrics;
    private final FoodEntryRepository foodEntries;
    private final MedicationRepository medications;
    private final AdherenceRepository adherence;
    private final DrugRepository drugs;

    public RecentActivityService(
        WorkoutProgramService programs,
        WorkoutScheduleService schedule,
        BodyCompositionRepository bodyComposition,
        DailyMetricRepository dailyMetrics,
        FoodEntryRepository foodEntries,
        MedicationRepository medications,
        AdherenceRepository adherence,
        DrugRepository drugs
    ) {
        this.programs = programs;
        this.schedule = schedule;
        this.bodyComposition = bodyComposition;
        this.dailyMetrics = dailyMetrics;
        this.foodEntries = foodEntries;
        this.medications = medications;
        this.adherence = adherence;
        this.drugs = drugs;
    }

    /** The {@code limit} most-recent activity rows for the user, newest first. */
    public List<RecentActivityResponse> recent(String userId, int limit) {
        List<RecentActivityResponse> events = new ArrayList<>();
        events.addAll(safe("workouts", () -> workouts(userId)));
        events.addAll(safe("weigh-ins", () -> weighIns(userId)));
        events.addAll(safe("sleep", () -> sleep(userId)));
        events.addAll(safe("food", () -> food(userId)));
        events.addAll(safe("medications", () -> medications(userId)));

        events.sort(Comparator.comparing(RecentActivityResponse::timestamp).reversed());
        return events.stream().limit(limit).toList();
    }

    // ── Sources ──────────────────────────────────────────────────────────

    private List<RecentActivityResponse> workouts(String userId) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(WORKOUT_LOOKBACK_DAYS);
        List<RecentActivityResponse> out = new ArrayList<>();
        for (WorkoutProgram p : programs.list(userId)) {
            for (ScheduledWorkout sw : schedule.calendar(userId, p.programId(), from, to)) {
                if (sw.status() != ScheduledStatus.COMPLETED) continue;
                Instant ts = performedAt(sw);
                if (ts == null) continue;
                out.add(new RecentActivityResponse(
                    ActivityKind.WORKOUT, sw.dayLabel() + " completed", workoutSubtitle(sw), ts));
            }
        }
        return out;
    }

    private List<RecentActivityResponse> weighIns(String userId) {
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(VITALS_LOOKBACK_DAYS));
        List<RecentActivityResponse> out = new ArrayList<>();
        for (BodyCompositionMeasurement m :
            bodyComposition.findByUserAndRange(userId, BodyCompositionMetric.WEIGHT_KG, from, to)) {
            if (m.sampleTime() == null) continue;
            String title = String.format(Locale.US, "Weighed in · %.1f lb", m.value() * LB_PER_KG);
            out.add(new RecentActivityResponse(
                ActivityKind.WEIGH_IN, title, m.sourcePlatform(), m.sampleTime()));
        }
        return out;
    }

    private List<RecentActivityResponse> sleep(String userId) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(VITALS_LOOKBACK_DAYS);
        List<RecentActivityResponse> out = new ArrayList<>();
        for (DailyMetric m : dailyMetrics.findByDateRange(userId, from, to)) {
            if (m.sleepMinutes() == null || m.date() == null) continue;
            int mins = m.sleepMinutes();
            String title = String.format(Locale.US, "Sleep · %dh %dm", mins / 60, mins % 60);
            String subtitle = m.sleepScore() != null ? "Score " + m.sleepScore() : null;
            out.add(new RecentActivityResponse(
                ActivityKind.SLEEP, title, subtitle, m.date().atStartOfDay(ZoneOffset.UTC).toInstant()));
        }
        return out;
    }

    private List<RecentActivityResponse> food(String userId) {
        LocalDate today = LocalDate.now();
        List<RecentActivityResponse> out = new ArrayList<>();
        for (int i = 0; i < FOOD_LOOKBACK_DAYS; i++) {
            for (FoodEntry e : foodEntries.findByDate(userId, today.minusDays(i))) {
                // Need a real log time to place it on the timeline; skip the
                // in-flight photo placeholder that hasn't been persisted/filled.
                if (e.createdAt() == null || e.isAnalyzing()) continue;
                out.add(new RecentActivityResponse(
                    ActivityKind.FOOD, "Logged " + e.foodName(), foodSubtitle(e), e.createdAt()));
            }
        }
        return out;
    }

    private List<RecentActivityResponse> medications(String userId) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(MED_LOOKBACK_DAYS);
        List<AdherenceLog> logs = adherence.findByUserAndDateRange(userId, from, to);
        if (logs.isEmpty()) return List.of();

        Map<String, Medication> medById = medications.findByUser(userId).stream()
            .collect(Collectors.toMap(Medication::medicationId, m -> m, (a, b) -> a));
        Map<String, Drug> drugById = drugs.findByIds(
            medById.values().stream().map(Medication::drugId).filter(d -> d != null).distinct().toList());

        List<RecentActivityResponse> out = new ArrayList<>();
        for (AdherenceLog logEntry : logs) {
            Medication med = medById.get(logEntry.medicationId());
            String drugName = medDisplayName(med, drugById);
            String unit = med != null ? med.unit() : "";
            if (logEntry.doses() == null) continue;
            for (DoseLog dose : logEntry.doses()) {
                if (dose.takenAt() == null) continue;
                String title = drugName + " · " + trimNumber(dose.dose()) + (unit.isEmpty() ? "" : " " + unit);
                out.add(new RecentActivityResponse(ActivityKind.MEDICATION, title, null, dose.takenAt()));
            }
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static Instant performedAt(ScheduledWorkout sw) {
        if (sw.completedAt() != null) return sw.completedAt();
        return sw.date() == null ? null : sw.date().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static String workoutSubtitle(ScheduledWorkout sw) {
        List<String> parts = new ArrayList<>();
        if (sw.durationSeconds() != null) parts.add(formatDuration(sw.durationSeconds()));
        int sets = totalLoggedSets(sw.session());
        if (sets > 0) parts.add(sets + " sets");
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private static int totalLoggedSets(WorkoutDay session) {
        if (session == null || session.blocks() == null) return 0;
        int n = 0;
        for (Block b : session.blocks()) {
            if (b.prescriptions() == null) continue;
            for (Prescription p : b.prescriptions()) {
                if (p.loggedSets() != null) n += p.loggedSets().size();
            }
        }
        return n;
    }

    /** Mirrors the web {@code formatDuration}: "45 min", "30s", or "2m 15s". */
    private static String formatDuration(int seconds) {
        if (seconds % 60 == 0) return (seconds / 60) + " min";
        if (seconds < 60) return seconds + "s";
        return (seconds / 60) + "m " + (seconds % 60) + "s";
    }

    private static String foodSubtitle(FoodEntry e) {
        String label = mealLabel(e.meal());
        Macros macros = e.macros();
        Double kcal = macros != null ? macros.caloriesKcal() : null;
        return kcal != null ? label + " · " + Math.round(kcal) + " kcal" : label;
    }

    private static String mealLabel(MealType meal) {
        if (meal == null) return "Meal";
        return switch (meal) {
            case BREAKFAST -> "Breakfast";
            case LUNCH -> "Lunch";
            case DINNER -> "Dinner";
            case SNACK -> "Snack";
        };
    }

    private static String medDisplayName(Medication med, Map<String, Drug> drugById) {
        if (med == null) return "Medication";
        if (med.customName() != null) return med.customName();
        Drug drug = med.drugId() != null ? drugById.get(med.drugId()) : null;
        return drug != null ? drug.name() : "Medication";
    }

    /** Render a dose amount without a trailing ".0" — "10", "2.5". */
    private static String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /** Run a source, degrading to empty (with a warning) if it throws. */
    private static List<RecentActivityResponse> safe(
        String source, Supplier<List<RecentActivityResponse>> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            log.warn("recent-activity source '{}' failed; omitting from feed", source, ex);
            return List.of();
        }
    }
}
