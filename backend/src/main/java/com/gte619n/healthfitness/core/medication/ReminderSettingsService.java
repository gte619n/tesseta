package com.gte619n.healthfitness.core.medication;

import java.time.LocalTime;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Reads and writes the user's medication-reminder settings. Reads always
 * return a complete object: a user who never configured anything gets the
 * defaults, and a stored document missing some window keys has them filled
 * from {@link ReminderSettings#defaultWindowTimes()}.
 */
@Service
public class ReminderSettingsService {

    private final ReminderSettingsRepository repository;

    public ReminderSettingsService(ReminderSettingsRepository repository) {
        this.repository = repository;
    }

    public ReminderSettings get(String userId) {
        requireUser(userId);
        return repository.find(userId)
            .map(ReminderSettingsService::completed)
            .orElseGet(() -> ReminderSettings.defaults(userId));
    }

    /** Replace the settings (PUT set-semantics). Returns the stored value. */
    public ReminderSettings set(
        String userId,
        boolean enabled,
        Map<TimeWindow, LocalTime> windowTimes,
        Map<String, ReminderSettings.MedicationOverride> perMedication
    ) {
        requireUser(userId);
        ReminderSettings settings = completed(new ReminderSettings(
            userId,
            enabled,
            windowTimes != null ? windowTimes : Map.of(),
            perMedication != null ? perMedication : Map.of(),
            null));
        repository.save(settings);
        return settings;
    }

    /** Fill any missing window with its built-in default time. */
    private static ReminderSettings completed(ReminderSettings s) {
        Map<TimeWindow, LocalTime> times = new EnumMap<>(TimeWindow.class);
        times.putAll(ReminderSettings.defaultWindowTimes());
        if (s.windowTimes() != null) {
            times.putAll(s.windowTimes());
        }
        return new ReminderSettings(
            s.userId(),
            s.enabled(),
            times,
            s.perMedication() != null ? Map.copyOf(s.perMedication()) : Map.of(),
            s.updatedAt());
    }

    private static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
    }
}
