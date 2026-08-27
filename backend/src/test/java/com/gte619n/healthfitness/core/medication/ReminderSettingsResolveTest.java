package com.gte619n.healthfitness.core.medication;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

// IMPL-21 (spec D2 / §6.4): the dose-time precedence rule —
// drug-setup explicit slot time → per-medication settings override → user window
// time → built-in default — verified server-side even though scheduling is
// Android-local.
class ReminderSettingsResolveTest {

    private static final String MED = "m1";

    @Test
    void slotExplicitTimeWinsOverEverything() {
        Map<TimeWindow, LocalTime> windowTimes = new EnumMap<>(TimeWindow.class);
        windowTimes.put(TimeWindow.MORNING, LocalTime.of(6, 0));
        ReminderSettings settings = new ReminderSettings(
            "u", true, windowTimes,
            Map.of(MED, new ReminderSettings.MedicationOverride(
                true, Map.of(TimeWindow.MORNING, LocalTime.of(7, 30)))),
            null);

        TimeSlot withExplicit = new TimeSlot(TimeWindow.MORNING, 100, LocalTime.of(9, 15));
        assertThat(settings.resolveDoseTime(MED, withExplicit)).isEqualTo(LocalTime.of(9, 15));
    }

    @Test
    void perMedOverrideWinsWhenNoSlotTime() {
        ReminderSettings settings = new ReminderSettings(
            "u", true, ReminderSettings.defaultWindowTimes(),
            Map.of(MED, new ReminderSettings.MedicationOverride(
                true, Map.of(TimeWindow.MORNING, LocalTime.of(7, 30)))),
            null);

        TimeSlot noTime = new TimeSlot(TimeWindow.MORNING, 100);
        assertThat(settings.resolveDoseTime(MED, noTime)).isEqualTo(LocalTime.of(7, 30));
    }

    @Test
    void fallsBackToWindowDefaultWhenNothingSet() {
        ReminderSettings settings = ReminderSettings.defaults("u");
        TimeSlot noTime = new TimeSlot(TimeWindow.AFTERNOON, 100);
        assertThat(settings.resolveDoseTime(MED, noTime)).isEqualTo(LocalTime.of(12, 0));
    }
}
