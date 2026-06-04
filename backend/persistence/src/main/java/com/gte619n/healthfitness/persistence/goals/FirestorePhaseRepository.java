package com.gte619n.healthfitness.persistence.goals;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.goals.Phase;
import com.gte619n.healthfitness.core.goals.PhaseRepository;
import com.gte619n.healthfitness.core.goals.PhaseStatus;
import com.gte619n.healthfitness.core.sync.SyncStatus;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
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

// Firestore-backed phase repository.
// Documents live at users/{userId}/goals/{goalId}/phases/{phaseId}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestorePhaseRepository implements PhaseRepository {

    private static final String SUBCOLLECTION = "phases";

    private final Firestore firestore;

    public FirestorePhaseRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<Phase> findById(String userId, String goalId, String phaseId) {
        DocumentSnapshot snapshot = await(collection(userId, goalId).document(phaseId).get());
        if (!snapshot.exists() || isArchived(snapshot)) return Optional.empty();
        return Optional.of(toPhase(goalId, snapshot));
    }

    @Override
    public List<Phase> findByGoal(String userId, String goalId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId, goalId)
            .orderBy("orderIndex", Query.Direction.ASCENDING)
            .limit(100)
            .get()).getDocuments();
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toPhase(goalId, d))
            .toList();
    }

    @Override
    public void save(String userId, Phase phase) {
        DocumentReference docRef = collection(userId, phase.goalId()).document(phase.phaseId());
        Map<String, Object> body = toBody(phase);
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String goalId, String phaseId) {
        // Soft-delete (tombstone) per IMPL-AND-20 D2.
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId, goalId).document(phaseId).set(updates, SetOptions.merge()));
    }

    private CollectionReference collection(String userId, String goalId) {
        return firestore
            .collection("users").document(userId)
            .collection("goals").document(goalId)
            .collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(Phase p) {
        Map<String, Object> body = new HashMap<>();
        body.put("goalId", p.goalId());
        body.put("title", p.title());
        body.put("description", p.description());
        body.put("orderIndex", p.orderIndex());
        body.put("status", p.status() != null ? p.status().name() : null);
        body.put("targetStartDate", p.targetStartDate() != null ? p.targetStartDate().toString() : null);
        body.put("targetEndDate", p.targetEndDate() != null ? p.targetEndDate().toString() : null);
        body.put("completedAt", p.completedAt());
        body.put("stepOrder", p.stepOrder() == null ? List.of() : p.stepOrder());
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        return body;
    }

    private static Phase toPhase(String goalId, DocumentSnapshot snapshot) {
        String status = snapshot.getString("status");
        String tsd = snapshot.getString("targetStartDate");
        String ted = snapshot.getString("targetEndDate");
        Long orderIndex = snapshot.getLong("orderIndex");
        return new Phase(
            goalId,
            snapshot.getId(),
            snapshot.getString("title"),
            snapshot.getString("description"),
            orderIndex != null ? orderIndex.intValue() : 0,
            status != null ? PhaseStatus.valueOf(status) : null,
            tsd != null ? LocalDate.parse(tsd) : null,
            ted != null ? LocalDate.parse(ted) : null,
            toInstant(snapshot.get("completedAt")),
            toStringList(snapshot.get("stepOrder"))
        );
    }

    private static List<String> toStringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(item.toString());
            }
        }
        return result;
    }

}
