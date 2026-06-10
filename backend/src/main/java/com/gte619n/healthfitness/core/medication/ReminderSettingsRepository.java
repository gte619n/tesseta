package com.gte619n.healthfitness.core.medication;

import java.util.Optional;

/** Port for the singleton medication-reminder settings document. */
public interface ReminderSettingsRepository {

    Optional<ReminderSettings> find(String userId);

    void save(ReminderSettings settings);
}
