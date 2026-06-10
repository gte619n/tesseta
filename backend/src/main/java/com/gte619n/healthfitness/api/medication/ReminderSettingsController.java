package com.gte619n.healthfitness.api.medication;

import com.gte619n.healthfitness.api.sync.SyncWriteContext;
import com.gte619n.healthfitness.core.auth.CurrentUserProvider;
import com.gte619n.healthfitness.core.medication.ReminderSettings;
import com.gte619n.healthfitness.core.medication.ReminderSettingsService;
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.gte619n.healthfitness.core.push.SyncChangeNotifier;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Medication-reminder settings: the user's default fire time per
 * {@link TimeWindow} plus optional per-medication overrides (custom times
 * and/or mute). Times on the wire are "HH:mm" strings, windows by enum name.
 * Scheduling happens on-device from this config — the backend only stores it.
 */
@RestController
@RequestMapping("/api/me/medications/reminder-settings")
@ConditionalOnProperty(name = "app.medications.enabled", havingValue = "true", matchIfMissing = true)
public class ReminderSettingsController {

    private final CurrentUserProvider currentUser;
    private final ReminderSettingsService settings;
    private final SyncWriteContext syncWrite;
    private final SyncChangeNotifier syncNotifier;

    public ReminderSettingsController(
        CurrentUserProvider currentUser,
        ReminderSettingsService settings,
        SyncWriteContext syncWrite,
        SyncChangeNotifier syncNotifier
    ) {
        this.currentUser = currentUser;
        this.settings = settings;
        this.syncWrite = syncWrite;
        this.syncNotifier = syncNotifier;
    }

    @GetMapping
    public ReminderSettingsDto get() {
        return ReminderSettingsDto.from(settings.get(currentUser.get().userId()));
    }

    /** PUT set-semantics (idempotent): replaces the whole settings document. */
    @PutMapping
    public ReminderSettingsDto put(@RequestBody ReminderSettingsDto body) {
        if (body == null) {
            throw new IllegalArgumentException("settings body is required");
        }
        String userId = currentUser.get().userId();
        ReminderSettings stored = settings.set(
            userId,
            body.enabled() == null || body.enabled(),
            toWindowTimes(body.windowTimes()),
            toOverrides(body.perMedication()));
        // Wake the user's other devices so their local reminder schedules replan.
        syncNotifier.changed(userId, syncWrite.originDeviceId(), "medicationReminderSettings");
        return ReminderSettingsDto.from(stored);
    }

    private static Map<TimeWindow, LocalTime> toWindowTimes(Map<String, String> raw) {
        Map<TimeWindow, LocalTime> times = new EnumMap<>(TimeWindow.class);
        if (raw != null) {
            raw.forEach((window, time) -> times.put(parseWindow(window), parseTime(time)));
        }
        return times;
    }

    private static Map<String, ReminderSettings.MedicationOverride> toOverrides(
        Map<String, ReminderSettingsDto.MedicationOverrideDto> raw) {
        Map<String, ReminderSettings.MedicationOverride> overrides = new HashMap<>();
        if (raw != null) {
            raw.forEach((medId, dto) -> overrides.put(medId, new ReminderSettings.MedicationOverride(
                dto.enabled() == null || dto.enabled(),
                toWindowTimes(dto.times()))));
        }
        return overrides;
    }

    private static TimeWindow parseWindow(String name) {
        try {
            return TimeWindow.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("unknown time window: " + name);
        }
    }

    private static LocalTime parseTime(String time) {
        try {
            return LocalTime.parse(time);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("invalid time (expected HH:mm): " + time);
        }
    }

    // ----- DTOs ---------------------------------------------------------

    public record ReminderSettingsDto(
        Boolean enabled,
        Map<String, String> windowTimes,
        Map<String, MedicationOverrideDto> perMedication
    ) {
        public record MedicationOverrideDto(
            Boolean enabled,
            Map<String, String> times
        ) {}

        static ReminderSettingsDto from(ReminderSettings s) {
            Map<String, String> windows = new HashMap<>();
            s.windowTimes().forEach((w, t) -> windows.put(w.name(), t.toString()));
            Map<String, MedicationOverrideDto> overrides = new HashMap<>();
            s.perMedication().forEach((medId, o) -> {
                Map<String, String> times = new HashMap<>();
                if (o.times() != null) {
                    o.times().forEach((w, t) -> times.put(w.name(), t.toString()));
                }
                overrides.put(medId, new MedicationOverrideDto(o.enabled(), times));
            });
            return new ReminderSettingsDto(s.enabled(), windows, overrides);
        }
    }
}
