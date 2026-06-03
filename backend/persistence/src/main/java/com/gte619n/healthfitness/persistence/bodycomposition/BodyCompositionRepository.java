package com.gte619n.healthfitness.persistence.bodycomposition;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMeasurement;
import com.gte619n.healthfitness.core.bodycomposition.BodyCompositionMetric;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteBatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Persists body composition measurements at
//   users/{userId}/bodyComposition/{recordId}
// One collection, all four metrics distinguished by the `metric` field.
// recordId == Google Health record name's last segment, so UPSERTs from
// webhook hydration are naturally idempotent.
//
// Composite index required: metric ASC, sampleTime DESC. Declared in
// infra/firestore/firestore.indexes.json.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class BodyCompositionRepository implements com.gte619n.healthfitness.core.bodycomposition.BodyCompositionRepository {

    private static final String SUBCOLLECTION = "bodyComposition";
    private static final int BATCH_SIZE = 500;

    private final Firestore firestore;

    public BodyCompositionRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<BodyCompositionMeasurement> findById(String userId, String recordId) {
        // recordId on its own is ambiguous (Google reuses the same id for
        // weight and body-fat at the same weigh-in), so this can't return
        // a single doc reliably. Callers that need a specific metric
        // should use findByUserAndRange. Kept for interface completeness.
        throw new UnsupportedOperationException(
            "findById is ambiguous without a metric; use findByUserAndRange");
    }

    @Override
    public List<BodyCompositionMeasurement> findByUserAndRange(
        String userId,
        BodyCompositionMetric metric,
        Instant from,
        Instant to
    ) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("metric", metric.name())
            .whereGreaterThanOrEqualTo("sampleTime", Timestamp.of(java.util.Date.from(from)))
            .whereLessThanOrEqualTo("sampleTime", Timestamp.of(java.util.Date.from(to)))
            .orderBy("sampleTime", Query.Direction.DESCENDING)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toMeasurement(userId, d))
            .toList();
    }

    @Override
    public List<BodyCompositionMeasurement> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .orderBy("sampleTime", Query.Direction.DESCENDING)
            .limit(500)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toMeasurement(userId, d))
            .toList();
    }

    @Override
    public Optional<BodyCompositionMeasurement> findLatest(String userId, BodyCompositionMetric metric) {
        // Indexed single-doc read: metric ASC, sampleTime DESC composite
        // index (declared in infra/firestore/firestore.indexes.json) lets
        // Firestore return just the newest row instead of paging 500.
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("metric", metric.name())
            .orderBy("sampleTime", Query.Direction.DESCENDING)
            .limit(20)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .findFirst()
            .map(d -> toMeasurement(userId, d));
    }

    @Override
    public void save(BodyCompositionMeasurement measurement) {
        DocumentReference docRef = collection(measurement.userId()).document(docId(measurement));
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(measurement, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void saveAll(List<BodyCompositionMeasurement> measurements) {
        if (measurements.isEmpty()) return;
        // Chunk into Firestore-batched writes of 500 (the limit per batch).
        for (int i = 0; i < measurements.size(); i += BATCH_SIZE) {
            List<BodyCompositionMeasurement> chunk =
                measurements.subList(i, Math.min(i + BATCH_SIZE, measurements.size()));
            WriteBatch batch = firestore.batch();
            for (BodyCompositionMeasurement m : chunk) {
                // saveAll is used by backfill / webhook hydration where we
                // accept that createdAt may be re-stamped on repeat runs.
                // Firestore's serverTimestamp is monotone enough that a
                // second touch within the same backfill won't surprise us.
                batch.set(
                    collection(m.userId()).document(docId(m)),
                    toBody(m, false),
                    SetOptions.merge());
            }
            await(batch.commit());
        }
    }

    @Override
    public void delete(String userId, String recordId) {
        // recordId alone isn't unique across metrics — see findById.
        throw new UnsupportedOperationException(
            "single-record delete needs metric; use deleteByUserMetricAndRange");
    }

    // Composite doc ID. Google Health emits the same recordId for weight
    // and body-fat at the same weigh-in, so keying on recordId alone made
    // body-fat saves overwrite the matching weight. Prefixing with the
    // metric keeps them in separate Firestore documents.
    private static String docId(BodyCompositionMeasurement m) {
        return m.metric().name() + "__" + m.recordId();
    }

    @Override
    public void deleteByUserMetricAndRange(
        String userId,
        BodyCompositionMetric metric,
        Instant from,
        Instant to
    ) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("metric", metric.name())
            .whereGreaterThanOrEqualTo("sampleTime", Timestamp.of(java.util.Date.from(from)))
            .whereLessThanOrEqualTo("sampleTime", Timestamp.of(java.util.Date.from(to)))
            .get()).getDocuments();
        if (docs.isEmpty()) return;
        WriteBatch batch = firestore.batch();
        for (QueryDocumentSnapshot d : docs) {
            batch.delete(d.getReference());
        }
        await(batch.commit());
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(BodyCompositionMeasurement m, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        // The doc ID is composite (metric + recordId); store the real
        // Google Health recordId as its own field so callers can read it
        // back without parsing the doc id.
        body.put("recordId", m.recordId());
        body.put("metric", m.metric().name());
        body.put("value", m.value());
        body.put("sampleTime", Timestamp.of(java.util.Date.from(m.sampleTime())));
        body.put("sourcePlatform", m.sourcePlatform());
        body.put("recordingMethod", m.recordingMethod());
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static BodyCompositionMeasurement toMeasurement(String userId, DocumentSnapshot snapshot) {
        // Prefer the explicit recordId field; fall back to parsing it out
        // of the composite doc id for documents written before the field
        // was added.
        String recordId = snapshot.getString("recordId");
        if (recordId == null) {
            String id = snapshot.getId();
            int sep = id.indexOf("__");
            recordId = sep >= 0 ? id.substring(sep + 2) : id;
        }
        return new BodyCompositionMeasurement(
            userId,
            recordId,
            BodyCompositionMetric.valueOf(snapshot.getString("metric")),
            snapshot.getDouble("value"),
            toInstant(snapshot.get("sampleTime")),
            snapshot.getString("sourcePlatform"),
            snapshot.getString("recordingMethod"),
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
