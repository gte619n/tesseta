package com.gte619n.healthfitness.persistence.progression;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.progression.PredictionLog;
import com.gte619n.healthfitness.core.progression.PredictionLogRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Shadow-mode prediction ledger at {@code users/{userId}/progressionPredictions/{id}}. */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestorePredictionLogRepository implements PredictionLogRepository {

    private final Firestore firestore;

    public FirestorePredictionLogRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection("progressionPredictions");
    }

    @Override
    public void save(PredictionLog log) {
        await(collection(log.userId()).document(log.id())
            .set(toBody(log), SetOptions.merge()));
    }

    @Override
    public List<PredictionLog> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId).get()).getDocuments();
        return docs.stream().map(d -> toLog(userId, d)).toList();
    }

    @Override
    public List<PredictionLog> findByModel(String userId, String model) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("model", model)
            .get()).getDocuments();
        return docs.stream().map(d -> toLog(userId, d)).toList();
    }

    private static Map<String, Object> toBody(PredictionLog l) {
        Map<String, Object> body = new HashMap<>();
        body.put("exerciseId", l.exerciseId());
        body.put("sessionId", l.sessionId());
        body.put("model", l.model());
        body.put("prescribedLoad", l.prescribedLoad());
        body.put("predictedReps", l.predictedReps());
        body.put("predictedRir", l.predictedRir());
        body.put("actualReps", l.actualReps());
        body.put("actualRir", l.actualRir());
        body.put("absoluteError", l.absoluteError());
        body.put("createdAt", l.createdAt() == null ? null : l.createdAt().toString());
        return body;
    }

    private static PredictionLog toLog(String userId, DocumentSnapshot s) {
        Long actualReps = s.getLong("actualReps");
        String createdAt = s.getString("createdAt");
        return new PredictionLog(
            s.getId(),
            userId,
            s.getString("exerciseId"),
            s.getString("sessionId"),
            s.getString("model"),
            s.getDouble("prescribedLoad") == null ? 0.0 : s.getDouble("prescribedLoad"),
            s.getDouble("predictedReps") == null ? 0.0 : s.getDouble("predictedReps"),
            s.getDouble("predictedRir"),
            actualReps == null ? null : actualReps.intValue(),
            s.getDouble("actualRir"),
            s.getDouble("absoluteError"),
            createdAt == null ? null : Instant.parse(createdAt)
        );
    }
}
