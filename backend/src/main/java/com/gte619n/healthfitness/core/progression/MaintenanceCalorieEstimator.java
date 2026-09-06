package com.gte619n.healthfitness.core.progression;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLog;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLogRepository;
import com.gte619n.healthfitness.core.user.BiologicalSex;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.core.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Estimates maintenance calories (IMPL-PROG-01 D7/M3). Primary path is ADAPTIVE:
 * back-calculate TDEE from observed intake vs. bodyweight change over a trailing
 * window ({@code TDEE = meanIntake − Δweight·3500/days}). Cold-start (too little
 * data) falls back to a dependency-free bodyweight heuristic ({@code 15·lb}),
 * since the user profile has no age/sex for Mifflin-St Jeor (M3).
 */
@Service
public class MaintenanceCalorieEstimator {

    private static final int WINDOW_DAYS = 28;
    private static final int MIN_NUTRITION_DAYS = 10;
    private static final double KCAL_PER_LB = 3500.0;
    private static final double KG_TO_LB = 2.2046226218;
    private static final double HEURISTIC_KCAL_PER_LB = 15.0;
    private static final double DEFAULT_BODYWEIGHT_LB = 175.0;
    /** Moderately-active TDEE multiplier on Mifflin-St Jeor BMR (M3). */
    private static final double ACTIVITY_MULTIPLIER = 1.5;

    private final NutritionDailyLogRepository nutrition;
    private final BodyCompositionRepository bodyComposition;
    private final UserRepository users;
    private final Clock clock;

    public MaintenanceCalorieEstimator(
        NutritionDailyLogRepository nutrition, BodyCompositionRepository bodyComposition,
        UserRepository users, Clock clock) {
        this.nutrition = nutrition;
        this.bodyComposition = bodyComposition;
        this.users = users;
        this.clock = clock;
    }

    /** Estimated maintenance kcal/day, adaptive when data allows, else heuristic. */
    public double estimate(String userId) {
        LocalDate today = LocalDate.now(clock);
        List<NutritionDailyLog> logs = nutrition.findByDateRange(userId, today.minusDays(WINDOW_DAYS), today);
        double bodyweightLb = currentBodyweightLb(userId);

        long loggedDays = logs.stream().filter(l -> l.caloriesKcal() != null && l.caloriesKcal() > 0).count();
        double meanIntake = logs.stream()
            .filter(l -> l.caloriesKcal() != null && l.caloriesKcal() > 0)
            .mapToDouble(NutritionDailyLog::caloriesKcal).average().orElse(0);

        Double weightChangeLb = weightChangeLb(userId);
        double coldStart = coldStart(userId, bodyweightLb);
        if (loggedDays >= MIN_NUTRITION_DAYS && weightChangeLb != null && meanIntake > 0) {
            double tdee = meanIntake - weightChangeLb * KCAL_PER_LB / WINDOW_DAYS;
            // Guard against absurd back-calculations (noisy scale) — clamp near the cold-start.
            if (tdee > 0.5 * coldStart && tdee < 2.0 * coldStart) return tdee;
        }
        return coldStart;
    }

    /**
     * Cold-start maintenance (M3): Mifflin-St Jeor × 1.5 when sex + DOB + weight
     * + height are all known, else the {@code 15 kcal/lb} bodyweight heuristic.
     */
    double coldStart(String userId, double bodyweightLb) {
        Double mifflin = mifflinTdee(userId, bodyweightLb);
        return mifflin != null ? mifflin : HEURISTIC_KCAL_PER_LB * bodyweightLb;
    }

    /** Mifflin-St Jeor BMR × activity, or null if any input is missing. */
    private Double mifflinTdee(String userId, double bodyweightLb) {
        Optional<User> u = users.findById(userId);
        if (u.isEmpty()) return null;
        User user = u.get();
        BiologicalSex sex = user.biologicalSex();
        LocalDate dob = user.dateOfBirth();
        Integer heightCm = user.heightCm();
        if (sex == null || dob == null || heightCm == null) return null;
        int age = Period.between(dob, LocalDate.now(clock)).getYears();
        if (age <= 0 || age > 120) return null;
        double weightKg = bodyweightLb / KG_TO_LB;
        // BMR = 10·kg + 6.25·cm − 5·age + s  (s = +5 male, −161 female)
        double bmr = 10 * weightKg + 6.25 * heightCm - 5.0 * age + (sex == BiologicalSex.MALE ? 5 : -161);
        return bmr * ACTIVITY_MULTIPLIER;
    }

    /** Mean intake over the trailing 14 days (the block loop's rolling balance input, §8.1). */
    public double meanIntake14d(String userId) {
        LocalDate today = LocalDate.now(clock);
        List<NutritionDailyLog> logs = nutrition.findByDateRange(userId, today.minusDays(14), today);
        return logs.stream()
            .filter(l -> l.caloriesKcal() != null && l.caloriesKcal() > 0)
            .mapToDouble(NutritionDailyLog::caloriesKcal).average().orElse(0);
    }

    /** Weight change (lb) over the window, or null if fewer than two spanning points. */
    private Double weightChangeLb(String userId) {
        Instant to = clock.instant();
        Instant from = to.minus(Duration.ofDays(WINDOW_DAYS));
        List<BodyCompositionMeasurement> raw =
            bodyComposition.findByUserAndRange(userId, BodyCompositionMetric.WEIGHT_KG, from, to);
        if (raw == null || raw.size() < 2) return null;
        List<BodyCompositionMeasurement> series = raw.stream()
            .filter(m -> m.sampleTime() != null)
            .sorted(java.util.Comparator.comparing(BodyCompositionMeasurement::sampleTime))
            .toList();
        if (series.size() < 2) return null;
        BodyCompositionMeasurement first = series.get(0);
        BodyCompositionMeasurement last = series.get(series.size() - 1);
        if (first.sampleTime() == null || last.sampleTime() == null) return null;
        if (ChronoUnit.DAYS.between(first.sampleTime(), last.sampleTime()) < 7) return null;
        return (last.value() - first.value()) * KG_TO_LB;
    }

    private double currentBodyweightLb(String userId) {
        return bodyComposition.findLatest(userId, BodyCompositionMetric.WEIGHT_KG)
            .map(m -> m.value() * KG_TO_LB)
            .orElse(DEFAULT_BODYWEIGHT_LB);
    }
}
