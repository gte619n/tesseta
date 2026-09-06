package com.gte619n.healthfitness.core.progression;

import java.util.List;
import java.util.Optional;

/** Per-(user, exercise) belief store. Firestore: {@code users/{uid}/progressionState/{exerciseId}}. */
public interface ProgressionStateRepository {
    Optional<ProgressionState> find(String userId, String exerciseId);
    List<ProgressionState> findAll(String userId);
    void save(ProgressionState state);
    void delete(String userId, String exerciseId);
}
