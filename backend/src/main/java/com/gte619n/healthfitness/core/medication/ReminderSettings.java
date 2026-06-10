package com.gte619n.healthfitness.core.medication;

import java.time.Instant;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;

/**
 * The user's medication-reminder configuration — deliberately a single
 * preferences document, NOT fields on {@link Medication}: reminder timing is a
 * user preference, not part of the medical record, and keeping it here leaves
 * the medication schema (and its history/audit log) untouched.
 *
 * <p>Stored at {@code users/{userId}/settings/medicationReminders}. Resolution
 * for "when does the reminder for medication M's {@code window} slot fire?":
 * per-medication override time → user-level window time → built-in default.
 * Reminders fire only when {@link #enabled} AND the medication's override (if
 * any) is enabled. Scheduling itself happens on-device (Android) from this
 * config plus the medication mirror.
 *
 * @param userId        owner
 * @param enabled       master switch (default true)
 * @param windowTimes   the user's default fire time per {@link TimeWindow};
 *                      missing windows fall back to {@link #defaultWindowTimes}
 * @param perMedication optional per-medication overrides keyed by medicationId
 * @param updatedAt     server timestamp of the last write (null until stored)
 */
public record ReminderSettings(
    String userId,
    boolean enabled,
    Map<TimeWindow, LocalTime> windowTimes,
    Map<String, MedicationOverride> perMedication,
    Instant updatedAt
) {

    /**
     * Per-medication override: mute one drug's reminders entirely
     * ({@code enabled=false}) and/or pin specific slots to custom times.
     */
    public record MedicationOverride(
        boolean enabled,
        Map<TimeWindow, LocalTime> times
    ) {}

    /** Built-in defaults: the start of each window, at a memorable time. */
    public static Map<TimeWindow, LocalTime> defaultWindowTimes() {
        Map<TimeWindow, LocalTime> times = new EnumMap<>(TimeWindow.class);
        times.put(TimeWindow.MORNING, LocalTime.of(6, 0));
        times.put(TimeWindow.AFTERNOON, LocalTime.of(12, 0));
        times.put(TimeWindow.EVENING, LocalTime.of(18, 0));
        times.put(TimeWindow.BEDTIME, LocalTime.of(21, 30));
        return times;
    }

    public static ReminderSettings defaults(String userId) {
        return new ReminderSettings(userId, true, defaultWindowTimes(), Map.of(), null);
    }

    /** The resolved fire time for one medication's window slot. */
    public LocalTime timeFor(String medicationId, TimeWindow window) {
        MedicationOverride override = perMedication != null
            ? perMedication.get(medicationId) : null;
        if (override != null && override.times() != null) {
            LocalTime custom = override.times().get(window);
            if (custom != null) {
                return custom;
            }
        }
        LocalTime user = windowTimes != null ? windowTimes.get(window) : null;
        return user != null ? user : defaultWindowTimes().get(window);
    }

    /** Whether reminders should fire at all for this medication. */
    public boolean enabledFor(String medicationId) {
        if (!enabled) {
            return false;
        }
        MedicationOverride override = perMedication != null
            ? perMedication.get(medicationId) : null;
        return override == null || override.enabled();
    }
}
