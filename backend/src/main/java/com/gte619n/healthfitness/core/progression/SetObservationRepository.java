package com.gte619n.healthfitness.core.progression;

import java.time.Instant;
import java.util.List;

/** Append-only observation log. Firestore: {@code users/{uid}/progressionObservations/{id}}. */
public interface SetObservationRepository {
    void save(SetObservation observation);
    void saveAll(List<SetObservation> observations);
    /** All observations for one exercise, oldest first. */
    List<SetObservation> findByExercise(String userId, String exerciseId);
    /** All observations for a user with completedAt in [from,to], oldest first. */
    List<SetObservation> findByUserSince(String userId, Instant from);
}
