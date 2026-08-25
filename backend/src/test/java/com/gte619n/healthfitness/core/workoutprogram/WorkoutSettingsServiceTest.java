package com.gte619n.healthfitness.core.workoutprogram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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

    @Test
    void setPreferences_roundTripsAndPreservesTarget() {
        WorkoutSettingsService svc = new WorkoutSettingsService(new InMemRepo());
        svc.setWeeklyStreakTarget("u1", 5);
        WorkoutSettings stored = svc.setPreferences("u1", "  no bent-over rows  ");
        assertEquals("no bent-over rows", stored.preferences()); // trimmed
        assertEquals(5, svc.get("u1").weeklyStreakTarget()); // target survived
        assertEquals("no bent-over rows", svc.get("u1").preferences());
    }

    @Test
    void setWeeklyStreakTarget_preservesPreferences() {
        WorkoutSettingsService svc = new WorkoutSettingsService(new InMemRepo());
        svc.setPreferences("u1", "avoid overhead press");
        svc.setWeeklyStreakTarget("u1", 3);
        assertEquals("avoid overhead press", svc.get("u1").preferences());
        assertEquals(3, svc.get("u1").weeklyStreakTarget());
    }

    @Test
    void setPreferences_blankClearsToNull() {
        WorkoutSettingsService svc = new WorkoutSettingsService(new InMemRepo());
        svc.setPreferences("u1", "something");
        svc.setPreferences("u1", "   ");
        assertNull(svc.get("u1").preferences());
    }

    @Test
    void setPreferences_capsLength() {
        WorkoutSettingsService svc = new WorkoutSettingsService(new InMemRepo());
        String tooLong = "x".repeat(WorkoutSettings.MAX_PREFERENCES_LENGTH + 500);
        WorkoutSettings stored = svc.setPreferences("u1", tooLong);
        assertEquals(WorkoutSettings.MAX_PREFERENCES_LENGTH, stored.preferences().length());
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
