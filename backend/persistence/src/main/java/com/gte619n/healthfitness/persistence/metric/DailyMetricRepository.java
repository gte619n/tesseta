package com.gte619n.healthfitness.persistence.metric;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.metric.DailyMetric;
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

// Persists day-grained activity / vitals metrics at
//   users/{userId}/dailyMetrics/{yyyy-MM-dd}
// One document per calendar day. Different metric families (steps, resting
// HR, HRV, sleep) arrive in separate Google Health notifications, so writes
// merge field-by-field: only the non-null fields of the incoming record are
// set, leaving previously-stored fields for that day intact.
//
// A `date` string field mirrors the document id so range queries use a
// single-field index (auto-created) rather than a composite one.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class DailyMetricRepository implements com.gte619n.healthfitness.core.metric.DailyMetricRepository {

    private static final String SUBCOLLECTION = "dailyMetrics";

    private final Firestore firestore;

    public DailyMetricRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<DailyMetric> findByDate(String userId, LocalDate date) {
        DocumentSnapshot snapshot = await(collection(userId).document(date.toString()).get());
        if (!snapshot.exists()) {
            return Optional.empty();
        }
        return Optional.of(toMetric(userId, snapshot));
    }

    @Override
    public List<DailyMetric> findByDateRange(String userId, LocalDate from, LocalDate to) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereGreaterThanOrEqualTo("date", from.toString())
            .whereLessThanOrEqualTo("date", to.toString())
            .orderBy("date", Query.Direction.ASCENDING)
            .get()).getDocuments();
        return docs.stream().map(d -> toMetric(userId, d)).toList();
    }

    @Override
    public void save(DailyMetric metric) {
        DocumentReference docRef = collection(metric.userId()).document(metric.date().toString());
        DocumentSnapshot existing = await(docRef.get());
        await(docRef.set(toBody(metric, !existing.exists()), SetOptions.merge()));
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(DailyMetric m, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", m.date().toString());
        // Merge semantics: only write the metric fields actually present on
        // this record so a steps-only update doesn't wipe a stored sleep score.
        putIfPresent(body, "steps", m.steps());
        putIfPresent(body, "restingHeartRate", m.restingHeartRate());
        putIfPresent(body, "sleepMinutes", m.sleepMinutes());
        putIfPresent(body, "hrvMs", m.hrvMs());
        putIfPresent(body, "sleepScore", m.sleepScore());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static void putIfPresent(Map<String, Object> body, String key, Integer value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    private static DailyMetric toMetric(String userId, DocumentSnapshot snapshot) {
        return new DailyMetric(
            userId,
            LocalDate.parse(snapshot.getString("date")),
            toInteger(snapshot.getLong("steps")),
            toInteger(snapshot.getLong("restingHeartRate")),
            toInteger(snapshot.getLong("sleepMinutes")),
            toInteger(snapshot.getLong("hrvMs")),
            toInteger(snapshot.getLong("sleepScore")),
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    private static Integer toInteger(Long value) {
        return value == null ? null : value.intValue();
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
