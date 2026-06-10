package com.gte619n.healthfitness.core.medication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link ReminderSettingsService} and the {@link ReminderSettings}
 * resolution rules (override time → user window time → built-in default).
 */
class ReminderSettingsServiceTest {

    private static final String USER = "u-rem";

    @Test
    void get_withoutStoredSettings_returnsCompleteDefaults() {
        ReminderSettingsService svc = new ReminderSettingsService(new InMemRepo());

        ReminderSettings s = svc.get(USER);

        assertTrue(s.enabled());
        assertEquals(LocalTime.of(6, 0), s.windowTimes().get(TimeWindow.MORNING));
        assertEquals(LocalTime.of(21, 30), s.windowTimes().get(TimeWindow.BEDTIME));
        assertEquals(4, s.windowTimes().size(), "every window has a time");
    }

    @Test
    void set_thenGet_roundTrips_andFillsMissingWindows() {
        ReminderSettingsService svc = new ReminderSettingsService(new InMemRepo());

        svc.set(USER, true,
            Map.of(TimeWindow.MORNING, LocalTime.of(5, 45)),
            Map.of("med-1", new ReminderSettings.MedicationOverride(
                true, Map.of(TimeWindow.MORNING, LocalTime.of(7, 15)))));

        ReminderSettings s = svc.get(USER);
        assertEquals(LocalTime.of(5, 45), s.windowTimes().get(TimeWindow.MORNING));
        assertEquals(LocalTime.of(12, 0), s.windowTimes().get(TimeWindow.AFTERNOON),
            "windows not supplied fall back to the built-in default");
        assertEquals(LocalTime.of(7, 15), s.timeFor("med-1", TimeWindow.MORNING),
            "a per-medication override beats the user window time");
        assertEquals(LocalTime.of(5, 45), s.timeFor("med-2", TimeWindow.MORNING),
            "medications without an override use the user window time");
    }

    @Test
    void enabledFor_respectsMasterSwitch_andPerMedMute() {
        ReminderSettings muted = new ReminderSettings(USER, true,
            ReminderSettings.defaultWindowTimes(),
            Map.of("med-1", new ReminderSettings.MedicationOverride(false, Map.of())),
            null);
        assertFalse(muted.enabledFor("med-1"), "a muted medication doesn't remind");
        assertTrue(muted.enabledFor("med-2"));

        ReminderSettings off = new ReminderSettings(USER, false,
            ReminderSettings.defaultWindowTimes(), Map.of(), null);
        assertFalse(off.enabledFor("med-2"), "the master switch mutes everything");
    }

    private static final class InMemRepo implements ReminderSettingsRepository {
        private final Map<String, ReminderSettings> rows = new ConcurrentHashMap<>();
        @Override public Optional<ReminderSettings> find(String userId) {
            return Optional.ofNullable(rows.get(userId));
        }
        @Override public void save(ReminderSettings settings) {
            rows.put(settings.userId(), settings);
        }
    }
}
