package com.gte619n.healthfitness.core.progression;

import java.util.Optional;

/** Current week-loop parameters per user. Firestore: {@code users/{uid}/progressionWeek/current}. */
public interface WeekParametersRepository {
    Optional<WeekParameters> find(String userId);
    void save(WeekParameters parameters);
}
