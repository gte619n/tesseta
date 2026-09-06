package com.gte619n.healthfitness.persistence.medication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.gte619n.healthfitness.core.medication.ReminderSettings;
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.gte619n.healthfitness.testsupport.firestore.FirestoreEmulatorExtension;
import com.google.cloud.firestore.Firestore;
import java.time.LocalTime;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Regression: {@code save()} listed {@code createdAt} in the merge mask
 * unconditionally but only put it in the body on create, and Firestore rejects a
 * mask path absent from the data ("Field masks contains invalid path") — so the
 * FIRST save of the settings doc succeeded and every later save failed 400,
 * making reminder settings effectively write-once (per-medication mutes could
 * never be added). Runs against the real emulator.
 */
@Tag("firestore-emulator")
@ExtendWith(FirestoreEmulatorExtension.class)
class ReminderSettingsRepositoryImplTest {

    private static final String USER = "user-1";

    @Test
    void savingOverAnExistingDocPersistsTheUpdate(Firestore firestore) {
        ReminderSettingsRepositoryImpl repo = new ReminderSettingsRepositoryImpl(firestore);
        repo.save(new ReminderSettings(
            USER, true, Map.of(TimeWindow.MORNING, LocalTime.of(6, 0)), Map.of(), null));

        ReminderSettings update = new ReminderSettings(
            USER, true,
            Map.of(TimeWindow.MORNING, LocalTime.of(7, 30)),
            Map.of("med-1", new ReminderSettings.MedicationOverride(false, Map.of())),
            null);
        assertThatCode(() -> repo.save(update)).doesNotThrowAnyException();

        ReminderSettings stored = repo.find(USER).orElseThrow();
        assertThat(stored.windowTimes()).containsEntry(TimeWindow.MORNING, LocalTime.of(7, 30));
        assertThat(stored.perMedication()).containsKey("med-1");
        assertThat(stored.perMedication().get("med-1").enabled()).isFalse();
    }

    @Test
    void removedOverridesActuallyGoAway(Firestore firestore) {
        ReminderSettingsRepositoryImpl repo = new ReminderSettingsRepositoryImpl(firestore);
        repo.save(new ReminderSettings(
            USER, true, Map.of(),
            Map.of("med-1", new ReminderSettings.MedicationOverride(false, Map.of())), null));

        repo.save(new ReminderSettings(USER, true, Map.of(), Map.of(), null));

        assertThat(repo.find(USER).orElseThrow().perMedication()).isEmpty();
    }
}
