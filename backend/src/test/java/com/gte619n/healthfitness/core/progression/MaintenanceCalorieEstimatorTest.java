package com.gte619n.healthfitness.core.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.user.BiologicalSex;
import com.gte619n.healthfitness.core.user.User;
import com.gte619n.healthfitness.testsupport.InMemoryBodyCompositionRepository;
import com.gte619n.healthfitness.testsupport.InMemoryUserRepository;
import com.gte619n.healthfitness.testsupport.nutrition.InMemoryNutritionDailyLogRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** Golden test for the M3 Mifflin-St Jeor cold-start vs. the heuristic fallback. */
class MaintenanceCalorieEstimatorTest {

    private static final String USER = "u1";
    private static final Instant NOW = LocalDate.of(2026, 9, 5).atStartOfDay(ZoneOffset.UTC).toInstant();

    private MaintenanceCalorieEstimator estimator(InMemoryUserRepository users) {
        InMemoryBodyCompositionRepository bodyComp = new InMemoryBodyCompositionRepository();
        // 200 lb ≈ 90.72 kg.
        bodyComp.save(new BodyCompositionMeasurement(USER, "w1", BodyCompositionMetric.WEIGHT_KG,
            200 / 2.2046226218, NOW, "MANUAL", "MANUAL", NOW, NOW));
        return new MaintenanceCalorieEstimator(
            new InMemoryNutritionDailyLogRepository(), bodyComp, users, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void mifflinWhenDemographicsPresent() {
        InMemoryUserRepository users = new InMemoryUserRepository();
        // Male, born 1990-01-01 (age 36 at 2026-09-05), 180 cm, 200 lb.
        users.save(new User(USER, "e@x.com", "E", null, 180, NOW, NOW, BiologicalSex.MALE,
            LocalDate.of(1990, 1, 1)));
        // BMR = 10·90.72 + 6.25·180 − 5·36 + 5 = 1857.2 ; ×1.5 = 2785.8
        assertEquals(2785.8, estimator(users).estimate(USER), 2.0);
    }

    @Test
    void heuristicFallbackWhenDemographicsMissing() {
        InMemoryUserRepository users = new InMemoryUserRepository();
        users.save(new User(USER, "e@x.com", "E", null, 180, NOW, NOW)); // no sex/dob
        // Heuristic: 15 kcal/lb × 200 lb = 3000.
        assertEquals(3000.0, estimator(users).estimate(USER), 1.0);
    }
}
