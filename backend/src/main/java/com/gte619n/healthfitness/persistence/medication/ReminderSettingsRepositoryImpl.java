package com.gte619n.healthfitness.persistence.medication;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.gte619n.healthfitness.core.medication.ReminderSettings;
import com.gte619n.healthfitness.core.medication.ReminderSettingsRepository;
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.SetOptions;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed reminder settings repository. A singleton preferences
 * document at users/{userId}/settings/medicationReminders; times are stored
 * as "HH:mm" strings and windows by enum name.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class ReminderSettingsRepositoryImpl implements ReminderSettingsRepository {

    private static final String SETTINGS = "settings";
    private static final String DOC_ID = "medicationReminders";

    private final Firestore firestore;

    public ReminderSettingsRepositoryImpl(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<ReminderSettings> find(String userId) {
        DocumentSnapshot snapshot = await(document(userId).get());
        if (!snapshot.exists()) {
            return Optional.empty();
        }
        return Optional.of(toSettings(userId, snapshot));
    }

    @Override
    public void save(ReminderSettings settings) {
        DocumentReference docRef = document(settings.userId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = new HashMap<>();
        body.put("enabled", settings.enabled());
        body.put("windowTimes", windowTimesBody(settings.windowTimes()));
        body.put("perMedication", perMedicationBody(settings.perMedication()));
        body.put("updatedAt", serverTimestamp());
        if (!existing.exists()) {
            body.put("createdAt", serverTimestamp());
        }
        // set() (not merge) for the maps so removed overrides actually go away.
        await(docRef.set(body, SetOptions.mergeFields(
            "enabled", "windowTimes", "perMedication", "updatedAt", "createdAt")));
    }

    private DocumentReference document(String userId) {
        return firestore.collection("users").document(userId)
            .collection(SETTINGS).document(DOC_ID);
    }

    private static Map<String, Object> windowTimesBody(Map<TimeWindow, LocalTime> times) {
        Map<String, Object> body = new HashMap<>();
        if (times != null) {
            times.forEach((window, time) -> body.put(window.name(), time.toString()));
        }
        return body;
    }

    private static Map<String, Object> perMedicationBody(
        Map<String, ReminderSettings.MedicationOverride> overrides) {
        Map<String, Object> body = new HashMap<>();
        if (overrides != null) {
            overrides.forEach((medId, override) -> {
                Map<String, Object> o = new HashMap<>();
                o.put("enabled", override.enabled());
                o.put("times", windowTimesBody(override.times()));
                body.put(medId, o);
            });
        }
        return body;
    }

    private static ReminderSettings toSettings(String userId, DocumentSnapshot snapshot) {
        Boolean enabled = snapshot.getBoolean("enabled");
        return new ReminderSettings(
            userId,
            enabled == null || enabled,
            toWindowTimes(snapshot.get("windowTimes")),
            toPerMedication(snapshot.get("perMedication")),
            toInstant(snapshot.get("updatedAt")));
    }

    private static Map<TimeWindow, LocalTime> toWindowTimes(Object raw) {
        Map<TimeWindow, LocalTime> times = new EnumMap<>(TimeWindow.class);
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                try {
                    times.put(TimeWindow.valueOf(String.valueOf(k)),
                        LocalTime.parse(String.valueOf(v)));
                } catch (IllegalArgumentException | java.time.format.DateTimeParseException ignored) {
                    // An unknown window or malformed time is dropped; the
                    // service layer fills the gap with the default.
                }
            });
        }
        return times;
    }

    private static Map<String, ReminderSettings.MedicationOverride> toPerMedication(Object raw) {
        Map<String, ReminderSettings.MedicationOverride> overrides = new HashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (v instanceof Map<?, ?> o) {
                    Object enabled = o.get("enabled");
                    overrides.put(String.valueOf(k), new ReminderSettings.MedicationOverride(
                        !(enabled instanceof Boolean b) || b,
                        toWindowTimes(o.get("times"))));
                }
            });
        }
        return overrides;
    }
}
