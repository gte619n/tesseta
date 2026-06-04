package com.gte619n.healthfitness.persistence.medication;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.medication.AdherenceLog;
import com.gte619n.healthfitness.core.medication.AdherenceRepository;
import com.gte619n.healthfitness.core.medication.DoseLog;
import com.gte619n.healthfitness.core.medication.TimeWindow;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.CollectionGroup;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Firestore-backed adherence log repository.
 * Documents live at users/{userId}/medications/{medicationId}/adherence/{date}.
 */
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class AdherenceRepositoryImpl implements AdherenceRepository {

    private final Firestore firestore;

    public AdherenceRepositoryImpl(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<AdherenceLog> findByDate(String userId, String medicationId, LocalDate date) {
        DocumentSnapshot snapshot = await(collection(userId, medicationId)
            .document(date.toString())
            .get());
        if (!snapshot.exists() || isArchived(snapshot)) return Optional.empty();
        return Optional.of(toAdherenceLog(userId, medicationId, snapshot));
    }

    @Override
    public List<AdherenceLog> findByDateRange(String userId, String medicationId, LocalDate from, LocalDate to) {
        List<QueryDocumentSnapshot> docs = await(collection(userId, medicationId)
            .whereGreaterThanOrEqualTo("date", from.toString())
            .whereLessThanOrEqualTo("date", to.toString())
            .orderBy("date", Query.Direction.DESCENDING)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toAdherenceLog(userId, medicationId, d))
            .toList();
    }

    @Override
    public List<AdherenceLog> findByUserAndDateRange(String userId, LocalDate from, LocalDate to) {
        // Query across all medications for this user in the date range
        // This uses a collection group query pattern
        List<AdherenceLog> results = new ArrayList<>();

        // First get all medication IDs for this user
        List<QueryDocumentSnapshot> medDocs = await(firestore
            .collection("users")
            .document(userId)
            .collection("medications")
            .get()).getDocuments();

        for (QueryDocumentSnapshot medDoc : medDocs) {
            String medicationId = medDoc.getId();
            List<AdherenceLog> logs = findByDateRange(userId, medicationId, from, to);
            results.addAll(logs);
        }

        return results;
    }

    @Override
    public void save(AdherenceLog log) {
        // set() (overwrite) re-stamps syncStatus=ACTIVE, so re-saving a log
        // for a previously-deleted date correctly resurrects it.
        Map<String, Object> body = toBody(log);
        await(collection(log.userId(), log.medicationId())
            .document(log.date().toString())
            .set(body));
    }

    @Override
    public void deleteByDate(String userId, String medicationId, LocalDate date) {
        // Soft-delete (tombstone) per IMPL-AND-20 D2.
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId, medicationId).document(date.toString())
            .set(updates, SetOptions.merge()));
    }

    private CollectionReference collection(String userId, String medicationId) {
        return firestore.collection("users")
            .document(userId)
            .collection("medications")
            .document(medicationId)
            .collection("adherence");
    }

    @SuppressWarnings("unchecked")
    private AdherenceLog toAdherenceLog(String userId, String medicationId, DocumentSnapshot snapshot) {
        List<Map<String, Object>> dosesRaw = (List<Map<String, Object>>) snapshot.get("doses");
        List<DoseLog> doses = dosesRaw != null
            ? dosesRaw.stream().map(this::parseDoseLog).toList()
            : List.of();

        return new AdherenceLog(
            userId,
            medicationId,
            LocalDate.parse(snapshot.getString("date")),
            doses,
            snapshot.getString("notes")
        );
    }

    private DoseLog parseDoseLog(Map<String, Object> map) {
        return new DoseLog(
            TimeWindow.valueOf((String) map.get("window")),
            toInstant(map.get("takenAt")),
            ((Number) map.get("dose")).doubleValue()
        );
    }

    private static Map<String, Object> toBody(AdherenceLog log) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", log.date().toString());
        body.put("notes", log.notes());
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());

        List<Map<String, Object>> dosesMap = new ArrayList<>();
        if (log.doses() != null) {
            for (DoseLog dose : log.doses()) {
                Map<String, Object> doseMap = new HashMap<>();
                doseMap.put("window", dose.window().name());
                doseMap.put("takenAt", com.google.cloud.Timestamp.ofTimeSecondsAndNanos(
                    dose.takenAt().getEpochSecond(),
                    dose.takenAt().getNano()
                ));
                doseMap.put("dose", dose.dose());
                dosesMap.add(doseMap);
            }
        }
        body.put("doses", dosesMap);

        return body;
    }

}
