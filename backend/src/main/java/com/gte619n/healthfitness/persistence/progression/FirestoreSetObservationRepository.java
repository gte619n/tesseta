package com.gte619n.healthfitness.persistence.progression;

import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteBatch;
import com.gte619n.healthfitness.core.progression.ContextFlag;
import com.gte619n.healthfitness.core.progression.RirSource;
import com.gte619n.healthfitness.core.progression.SetObservation;
import com.gte619n.healthfitness.core.progression.SetObservationRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Append-only observation log at {@code users/{userId}/progressionObservations/{id}}.
 * {@code completedAt} is stored as an ISO string so lexicographic range queries work.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreSetObservationRepository implements SetObservationRepository {

    /** Firestore commits at most 500 writes per batch. */
    private static final int MAX_BATCH = 500;

    private final Firestore firestore;

    public FirestoreSetObservationRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection("progressionObservations");
    }

    @Override
    public void save(SetObservation observation) {
        await(collection(observation.userId()).document(observation.id())
            .set(toBody(observation), SetOptions.merge()));
    }

    @Override
    public void saveAll(List<SetObservation> observations) {
        if (observations.isEmpty()) {
            return;
        }
        for (int start = 0; start < observations.size(); start += MAX_BATCH) {
            WriteBatch batch = firestore.batch();
            for (SetObservation o : observations.subList(start, Math.min(start + MAX_BATCH, observations.size()))) {
                batch.set(collection(o.userId()).document(o.id()), toBody(o), SetOptions.merge());
            }
            await(batch.commit());
        }
    }

    @Override
    public List<SetObservation> findByExercise(String userId, String exerciseId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("exerciseId", exerciseId)
            .orderBy("completedAt", Query.Direction.ASCENDING)
            .get()).getDocuments();
        return docs.stream().map(d -> toObservation(userId, d)).toList();
    }

    @Override
    public List<SetObservation> findByUserSince(String userId, Instant from) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereGreaterThanOrEqualTo("completedAt", from.toString())
            .orderBy("completedAt", Query.Direction.ASCENDING)
            .get()).getDocuments();
        return docs.stream().map(d -> toObservation(userId, d)).toList();
    }

    private static Map<String, Object> toBody(SetObservation o) {
        Map<String, Object> body = new HashMap<>();
        body.put("sessionId", o.sessionId());
        body.put("exerciseId", o.exerciseId());
        body.put("setIndex", o.setIndex());
        body.put("isLastWorkingSet", o.isLastWorkingSet());
        body.put("load", o.load());
        body.put("reps", o.reps());
        body.put("rirSource", o.rirSource() == null ? null : o.rirSource().name());
        body.put("rir", o.rir());
        body.put("meanConcentricVelocity", o.meanConcentricVelocity());
        body.put("completedAt", o.completedAt() == null ? null : o.completedAt().toString());
        List<String> flags = o.contextFlags() == null
            ? List.of()
            : o.contextFlags().stream().map(ContextFlag::name).toList();
        body.put("contextFlags", flags);
        return body;
    }

    private static SetObservation toObservation(String userId, DocumentSnapshot s) {
        Long setIndex = s.getLong("setIndex");
        Long reps = s.getLong("reps");
        String rirSource = s.getString("rirSource");
        String completedAt = s.getString("completedAt");
        Set<ContextFlag> flags = new HashSet<>();
        Object rawFlags = s.get("contextFlags");
        if (rawFlags instanceof List<?> list) {
            for (Object f : list) {
                if (f != null) {
                    flags.add(ContextFlag.valueOf(f.toString()));
                }
            }
        }
        return new SetObservation(
            s.getId(),
            userId,
            s.getString("sessionId"),
            s.getString("exerciseId"),
            setIndex == null ? 0 : setIndex.intValue(),
            Boolean.TRUE.equals(s.getBoolean("isLastWorkingSet")),
            s.getDouble("load") == null ? 0.0 : s.getDouble("load"),
            reps == null ? null : reps.intValue(),
            rirSource == null ? null : RirSource.valueOf(rirSource),
            s.getDouble("rir"),
            s.getDouble("meanConcentricVelocity"),
            completedAt == null ? null : Instant.parse(completedAt),
            flags
        );
    }
}
