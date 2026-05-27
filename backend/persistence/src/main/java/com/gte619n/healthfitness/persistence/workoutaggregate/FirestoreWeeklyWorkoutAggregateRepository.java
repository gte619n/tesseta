package com.gte619n.healthfitness.persistence.workoutaggregate;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregate;
import com.gte619n.healthfitness.core.workoutaggregate.WeeklyWorkoutAggregateRepository;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Firestore-backed weekly workout aggregate repository.
// Documents live at users/{userId}/weeklyWorkoutAggregates/{yyyy-MM-dd} (Monday ISO week start).
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreWeeklyWorkoutAggregateRepository implements WeeklyWorkoutAggregateRepository {

    private static final String SUBCOLLECTION = "weeklyWorkoutAggregates";

    private final Firestore firestore;

    public FirestoreWeeklyWorkoutAggregateRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<WeeklyWorkoutAggregate> findByWeekStart(String userId, LocalDate weekStart) {
        DocumentSnapshot snapshot = await(collection(userId).document(weekStart.toString()).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toAggregate(userId, snapshot));
    }

    @Override
    public List<WeeklyWorkoutAggregate> findByDateRange(String userId, LocalDate from, LocalDate to) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .orderBy("weekStart", Query.Direction.ASCENDING)
            .whereGreaterThanOrEqualTo("weekStart", from.toString())
            .whereLessThanOrEqualTo("weekStart", to.toString())
            .get()).getDocuments();
        return docs.stream().map(d -> toAggregate(userId, d)).toList();
    }

    @Override
    public void save(WeeklyWorkoutAggregate aggregate) {
        DocumentReference docRef = collection(aggregate.userId()).document(aggregate.weekStart().toString());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(aggregate, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(WeeklyWorkoutAggregate agg, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("weekStart", agg.weekStart().toString());
        body.put("totalTonnage", agg.totalTonnage());
        body.put("sessionCount", agg.sessionCount());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static WeeklyWorkoutAggregate toAggregate(String userId, DocumentSnapshot snapshot) {
        return new WeeklyWorkoutAggregate(
            userId,
            LocalDate.parse(snapshot.getString("weekStart")),
            snapshot.getDouble("totalTonnage"),
            snapshot.getLong("sessionCount") != null
                ? snapshot.getLong("sessionCount").intValue()
                : null,
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    private static <T> T await(ApiFuture<T> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Firestore call interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Firestore call failed", e.getCause());
        }
    }
}
