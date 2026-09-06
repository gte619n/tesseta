package com.gte619n.healthfitness.core.progression;

import java.util.List;

/** Shadow-mode prediction ledger. Firestore: {@code users/{uid}/progressionPredictions/{id}}. */
public interface PredictionLogRepository {
    void save(PredictionLog log);
    List<PredictionLog> findByUser(String userId);
    List<PredictionLog> findByModel(String userId, String model);
}
