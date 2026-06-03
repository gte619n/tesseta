package com.gte619n.healthfitness.persistence.goals;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.SYNC_STATUS_KEY;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.isArchived;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.goals.Goal;
import com.gte619n.healthfitness.core.goals.GoalDomain;
import com.gte619n.healthfitness.core.goals.GoalRepository;
import com.gte619n.healthfitness.core.goals.GoalSource;
import com.gte619n.healthfitness.core.goals.GoalStatus;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Firestore-backed goal repository.
// Documents live at users/{userId}/goals/{goalId}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreGoalRepository implements GoalRepository {

    private static final String SUBCOLLECTION = "goals";

    private final Firestore firestore;

    public FirestoreGoalRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<Goal> findById(String userId, String goalId) {
        DocumentSnapshot snapshot = await(collection(userId).document(goalId).get());
        if (!snapshot.exists() || isArchived(snapshot)) return Optional.empty();
        return Optional.of(toGoal(userId, snapshot));
    }

    @Override
    public List<Goal> findByUser(String userId, GoalStatus status) {
        Query query = collection(userId).orderBy("createdAt", Query.Direction.DESCENDING);
        if (status != null) {
            query = query.whereEqualTo("status", status.name());
        }
        List<QueryDocumentSnapshot> docs = await(query.limit(200).get()).getDocuments();
        // "status" filtered above is the domain GoalStatus; sync tombstones
        // (syncStatus=ARCHIVED) are excluded here regardless.
        return docs.stream()
            .filter(d -> !isArchived(d))
            .map(d -> toGoal(userId, d))
            .toList();
    }

    @Override
    public void save(Goal goal) {
        DocumentReference docRef = collection(goal.userId()).document(goal.goalId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(goal, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String goalId) {
        // Soft-delete (tombstone) per IMPL-AND-20 D2: flip the sync lifecycle
        // syncStatus to ARCHIVED (distinct from the domain GoalStatus.ARCHIVED
        // that GoalService.archive sets, which keeps the goal live/listable).
        Map<String, Object> updates = new HashMap<>();
        updates.put(SYNC_STATUS_KEY, SyncStatus.ARCHIVED.name());
        updates.put("updatedAt", serverTimestamp());
        await(collection(userId).document(goalId).set(updates, SetOptions.merge()));
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(Goal g, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", g.title());
        body.put("description", g.description());
        body.put("domain", g.domain() != null ? g.domain().name() : null);
        body.put("status", g.status() != null ? g.status().name() : null);
        body.put("startDate", g.startDate() != null ? g.startDate().toString() : null);
        body.put("targetDate", g.targetDate() != null ? g.targetDate().toString() : null);
        body.put("completedAt", g.completedAt());
        body.put("phaseOrder", g.phaseOrder() == null ? List.of() : g.phaseOrder());
        body.put("source", g.source() != null ? g.source().name() : null);
        body.put(SYNC_STATUS_KEY, SyncStatus.ACTIVE.name());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static Goal toGoal(String userId, DocumentSnapshot snapshot) {
        String domain = snapshot.getString("domain");
        String status = snapshot.getString("status");
        String startDate = snapshot.getString("startDate");
        String targetDate = snapshot.getString("targetDate");
        String source = snapshot.getString("source");
        return new Goal(
            userId,
            snapshot.getId(),
            snapshot.getString("title"),
            snapshot.getString("description"),
            domain != null ? GoalDomain.valueOf(domain) : null,
            status != null ? GoalStatus.valueOf(status) : null,
            startDate != null ? LocalDate.parse(startDate) : null,
            targetDate != null ? LocalDate.parse(targetDate) : null,
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt")),
            toInstant(snapshot.get("completedAt")),
            toStringList(snapshot.get("phaseOrder")),
            source != null ? GoalSource.valueOf(source) : null
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
