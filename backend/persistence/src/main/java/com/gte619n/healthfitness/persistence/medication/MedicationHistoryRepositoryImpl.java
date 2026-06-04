package com.gte619n.healthfitness.persistence.medication;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.medication.ChangeType;
import com.gte619n.healthfitness.core.medication.MedicationHistory;
import com.gte619n.healthfitness.core.medication.MedicationHistoryRepository;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed medication history repository.
 * Documents live at users/{userId}/medications/{medicationId}/history/{historyId}.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class MedicationHistoryRepositoryImpl implements MedicationHistoryRepository {

    private final Firestore firestore;

    public MedicationHistoryRepositoryImpl(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public List<MedicationHistory> findByMedication(String userId, String medicationId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId, medicationId)
            .orderBy("changedAt", Query.Direction.DESCENDING)
            .limit(100)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toHistory(userId, medicationId, d))
            .toList();
    }

    @Override
    public void save(MedicationHistory history) {
        Map<String, Object> body = toBody(history);
        await(collection(history.userId(), history.medicationId())
            .document(history.historyId())
            .set(body));
    }

    private CollectionReference collection(String userId, String medicationId) {
        return firestore.collection("users")
            .document(userId)
            .collection("medications")
            .document(medicationId)
            .collection("history");
    }

    private static MedicationHistory toHistory(String userId, String medicationId, DocumentSnapshot snapshot) {
        return new MedicationHistory(
            snapshot.getId(),
            userId,
            medicationId,
            ChangeType.valueOf(snapshot.getString("changeType")),
            snapshot.getString("previousValue"),
            snapshot.getString("newValue"),
            toInstant(snapshot.get("changedAt")),
            snapshot.getString("notes")
        );
    }

    private static Map<String, Object> toBody(MedicationHistory h) {
        Map<String, Object> body = new HashMap<>();
        body.put("changeType", h.changeType().name());
        body.put("previousValue", h.previousValue());
        body.put("newValue", h.newValue());
        body.put("changedAt", serverTimestamp());
        body.put("notes", h.notes());
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        return body;
    }

}
