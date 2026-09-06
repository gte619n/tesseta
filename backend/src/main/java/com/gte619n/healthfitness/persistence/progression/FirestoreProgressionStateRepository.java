package com.gte619n.healthfitness.persistence.progression;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.progression.ProgressionState;
import com.gte619n.healthfitness.core.progression.ProgressionStateRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/** Per-(user, exercise) belief store at {@code users/{userId}/progressionState/{exerciseId}}. */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreProgressionStateRepository implements ProgressionStateRepository {

    private final Firestore firestore;

    public FirestoreProgressionStateRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection("progressionState");
    }

    @Override
    public Optional<ProgressionState> find(String userId, String exerciseId) {
        DocumentSnapshot snap = await(collection(userId).document(exerciseId).get());
        return snap.exists() ? Optional.of(toState(userId, snap)) : Optional.empty();
    }

    @Override
    public List<ProgressionState> findAll(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId).get()).getDocuments();
        return docs.stream().map(d -> toState(userId, d)).toList();
    }

    @Override
    public void save(ProgressionState state) {
        await(collection(state.userId()).document(state.exerciseId())
            .set(toBody(state), SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String exerciseId) {
        await(collection(userId).document(exerciseId).delete());
    }

    private static Map<String, Object> toBody(ProgressionState s) {
        Map<String, Object> body = new HashMap<>();
        body.put("exerciseId", s.exerciseId());
        body.put("e1rmLbs", s.e1rmLbs());
        body.put("sigmaLbs", s.sigmaLbs());
        body.put("lastObservedAt", s.lastObservedAt() == null ? null : s.lastObservedAt().toString());
        body.put("observationCount", s.observationCount());
        body.put("consecutiveMissedSessions", s.consecutiveMissedSessions());
        body.put("kalmanEligibleAt", s.kalmanEligibleAt() == null ? null : s.kalmanEligibleAt().toString());
        body.put("version", s.version());
        return body;
    }

    private static ProgressionState toState(String userId, DocumentSnapshot s) {
        String lastObservedAt = s.getString("lastObservedAt");
        String kalmanEligibleAt = s.getString("kalmanEligibleAt");
        Double e1rm = s.getDouble("e1rmLbs");
        Double sigma = s.getDouble("sigmaLbs");
        Long obsCount = s.getLong("observationCount");
        Long missed = s.getLong("consecutiveMissedSessions");
        Long version = s.getLong("version");
        return new ProgressionState(
            userId,
            s.getString("exerciseId"),
            e1rm == null ? 0.0 : e1rm,
            sigma == null ? 0.0 : sigma,
            lastObservedAt == null ? null : Instant.parse(lastObservedAt),
            obsCount == null ? 0 : obsCount.intValue(),
            missed == null ? 0 : missed.intValue(),
            kalmanEligibleAt == null ? null : Instant.parse(kalmanEligibleAt),
            version == null ? 0 : version
        );
    }
}
