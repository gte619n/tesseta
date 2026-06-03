package com.gte619n.healthfitness.persistence.blood;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.blood.BloodMarker;
import com.gte619n.healthfitness.core.blood.BloodReading;
import com.gte619n.healthfitness.core.sync.SyncStatus;
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

// Firestore-backed blood reading repository.
// Documents live at users/{userId}/bloodReadings/{readingId}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class BloodReadingRepository implements com.gte619n.healthfitness.core.blood.BloodReadingRepository {

    private static final String SUBCOLLECTION = "bloodReadings";

    private final Firestore firestore;

    public BloodReadingRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<BloodReading> findById(String userId, String readingId) {
        DocumentSnapshot snapshot = await(collection(userId).document(readingId).get());
        if (!snapshot.exists() || isArchived(snapshot)) return Optional.empty();
        return Optional.of(toReading(userId, snapshot));
    }

    @Override
    public List<BloodReading> findByUser(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .orderBy("sampleDate", Query.Direction.DESCENDING)
            .limit(500)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toReading(userId, d))
            .toList();
    }

    @Override
    public Optional<BloodReading> findLatestByMarker(String userId, BloodMarker marker) {
        // Indexed read: marker ASC, sampleDate DESC composite index (declared
        // in infra/firestore/firestore.indexes.json). Fetch a small window
        // rather than limit(1) so a freshly-archived newest row doesn't mask
        // an older live reading (tombstones are filtered in application code
        // per D13 — inequality filters would drop legacy docs lacking the
        // syncStatus field).
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .whereEqualTo("marker", marker.name())
            .orderBy("sampleDate", Query.Direction.DESCENDING)
            .limit(20)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .findFirst()
            .map(d -> toReading(userId, d));
    }

    @Override
    public void save(BloodReading reading) {
        DocumentReference docRef = collection(reading.userId()).document(reading.readingId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(reading, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String readingId) {
        // Soft-delete (tombstone) per IMPL-AND-20 D2: never hard-delete the
        // doc; flip syncStatus to ARCHIVED and bump the sync cursor so the
        // delta API can tell offline clients to drop the row.
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId).document(readingId).set(updates, SetOptions.merge()));
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(BloodReading r, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("marker", r.marker().name());
        body.put("value", r.value());
        body.put("unit", r.unit());
        body.put("sampleDate", r.sampleDate().toString());
        body.put("labSource", r.labSource());
        body.put("notes", r.notes());
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static BloodReading toReading(String userId, DocumentSnapshot snapshot) {
        return new BloodReading(
            userId,
            snapshot.getId(),
            BloodMarker.valueOf(snapshot.getString("marker")),
            snapshot.getDouble("value"),
            snapshot.getString("unit"),
            LocalDate.parse(snapshot.getString("sampleDate")),
            snapshot.getString("labSource"),
            snapshot.getString("notes"),
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
