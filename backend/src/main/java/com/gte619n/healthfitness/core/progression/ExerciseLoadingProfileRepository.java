package com.gte619n.healthfitness.core.progression;

import java.util.Optional;

/**
 * Per-(user, exercise) equipment override store (D8). Absent doc ⇒ the resolver
 * derives defaults from the exercise's equipment. Firestore:
 * {@code users/{uid}/exerciseLoadingProfiles/{exerciseId}}.
 */
public interface ExerciseLoadingProfileRepository {
    Optional<ExerciseLoadingProfile> find(String userId, String exerciseId);
    void save(ExerciseLoadingProfile profile);
}
