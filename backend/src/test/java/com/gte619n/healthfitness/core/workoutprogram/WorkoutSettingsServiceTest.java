package com.gte619n.healthfitness.core.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link WorkoutSettingsService} with an in-memory repository: a user
 * who never configured anything gets the default target, writes round-trip, and
 * out-of-range targets are clamped into the supported range.
 */
class WorkoutSettingsServiceTest {

    @Test
    void get_returnsDefaultsWhenUnset() {
        WorkoutSettingsService svc = new WorkoutSettingsService(new InMemRepo());
        assertEquals(WorkoutSettings.DEFAULT_TARGET, svc.get("u1").weeklyStreakTarget());
    }

    @Test
    void set_roundTripsTheTarget() {
        WorkoutSettingsService svc = new WorkoutSettingsService(new InMemRepo());
        svc.setWeeklyStreakTarget("u1", 5);
        assertEquals(5, svc.get("u1").weeklyStreakTarget());
    }

    @Test
    void set_clampsBelowMinimum() {
        WorkoutSettingsService svc = new WorkoutSettingsService(new InMemRepo());
        WorkoutSettings stored = svc.setWeeklyStreakTarget("u1", 0);
        assertEquals(WorkoutSettings.MIN_TARGET, stored.weeklyStreakTarget());
    }

    @Test
    void set_clampsAboveMaximum() {
        WorkoutSettingsService svc = new WorkoutSettingsService(new InMemRepo());
        WorkoutSettings stored = svc.setWeeklyStreakTarget("u1", 99);
        assertEquals(WorkoutSettings.MAX_TARGET, stored.weeklyStreakTarget());
    }

    private static final class InMemRepo implements WorkoutSettingsRepository {
        private final Map<String, WorkoutSettings> rows = new HashMap<>();
        @Override public Optional<WorkoutSettings> find(String userId) {
            return Optional.ofNullable(rows.get(userId));
        }
        @Override public void save(WorkoutSettings settings) {
            rows.put(settings.userId(), settings);
        }
    }
}
