package com.gte619n.healthfitness.persistence.goals;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.goals.Comparator;
import com.gte619n.healthfitness.core.goals.Step;
import com.gte619n.healthfitness.core.goals.StepKind;
import com.gte619n.healthfitness.core.goals.StepMetricBinding;
import com.gte619n.healthfitness.core.goals.StepRepository;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Firestore-backed step repository.
// Documents live at users/{userId}/goals/{goalId}/phases/{phaseId}/steps/{stepId}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreStepRepository implements StepRepository {

    private static final String SUBCOLLECTION = "steps";

    private final Firestore firestore;

    public FirestoreStepRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<Step> findById(String userId, String goalId, String phaseId, String stepId) {
        DocumentSnapshot snapshot = await(collection(userId, goalId, phaseId).document(stepId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toStep(goalId, phaseId, snapshot));
    }

    @Override
    public List<Step> findByPhase(String userId, String goalId, String phaseId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId, goalId, phaseId)
            .orderBy("orderIndex", Query.Direction.ASCENDING)
            .limit(200)
            .get()).getDocuments();
        return docs.stream().map(d -> toStep(goalId, phaseId, d)).toList();
    }

    @Override
    public List<Step> findByGoal(String userId, String goalId) {
        // Walk the phases under this Goal, then read each phase's steps subcollection.
        List<QueryDocumentSnapshot> phaseDocs = await(firestore
            .collection("users").document(userId)
            .collection("goals").document(goalId)
            .collection("phases")
            .get()
        ).getDocuments();
        List<Step> result = new java.util.ArrayList<>();
        for (QueryDocumentSnapshot phaseDoc : phaseDocs) {
            String phaseId = phaseDoc.getId();
            List<QueryDocumentSnapshot> stepDocs = await(phaseDoc.getReference()
                .collection(SUBCOLLECTION)
                .orderBy("orderIndex", Query.Direction.ASCENDING)
                .get()).getDocuments();
            for (QueryDocumentSnapshot s : stepDocs) {
                result.add(toStep(goalId, phaseId, s));
            }
        }
        return result;
    }

    @Override
    public List<Step> findByMetricKey(String userId, String metricKey) {
        // Collection-group query — requires Firestore index on metric.metricKey.
        // Also filter by user via the path prefix.
        List<QueryDocumentSnapshot> docs = await(firestore
            .collectionGroup(SUBCOLLECTION)
            .whereEqualTo("metric.metricKey", metricKey)
            .get()
        ).getDocuments();
        return docs.stream()
            .filter(d -> isUnderUser(d, userId))
            .map(this::toStepFromGroupQuery)
            .toList();
    }

    @Override
    public List<Step> findAllSustained(String userId) {
        // Collection-group query — requires Firestore index on kind.
        List<QueryDocumentSnapshot> docs = await(firestore
            .collectionGroup(SUBCOLLECTION)
            .whereEqualTo("kind", StepKind.SUSTAINED.name())
            .get()
        ).getDocuments();
        return docs.stream()
            .filter(d -> isUnderUser(d, userId))
            .map(this::toStepFromGroupQuery)
            .toList();
    }

    @Override
    public void save(String userId, Step step) {
        DocumentReference docRef = collection(userId, step.goalId(), step.phaseId())
            .document(step.stepId());
        Map<String, Object> body = toBody(step);
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String goalId, String phaseId, String stepId) {
        await(collection(userId, goalId, phaseId).document(stepId).delete());
    }

    private CollectionReference collection(String userId, String goalId, String phaseId) {
        return firestore
            .collection("users").document(userId)
            .collection("goals").document(goalId)
            .collection("phases").document(phaseId)
            .collection(SUBCOLLECTION);
    }

    private static boolean isUnderUser(DocumentSnapshot doc, String userId) {
        // path: users/{userId}/goals/{goalId}/phases/{phaseId}/steps/{stepId}
        DocumentReference ref = doc.getReference();
        DocumentReference phaseDoc = ref.getParent().getParent();           // phases/{phaseId}
        if (phaseDoc == null) return false;
        DocumentReference goalDoc = phaseDoc.getParent().getParent();       // goals/{goalId}
        if (goalDoc == null) return false;
        DocumentReference userDoc = goalDoc.getParent().getParent();        // users/{userId}
        if (userDoc == null) return false;
        return userId.equals(userDoc.getId());
    }

    private Step toStepFromGroupQuery(QueryDocumentSnapshot doc) {
        DocumentReference ref = doc.getReference();
        String phaseId = ref.getParent().getParent().getId();
        String goalId = ref.getParent().getParent().getParent().getParent().getId();
        return toStep(goalId, phaseId, doc);
    }

    private static Map<String, Object> toBody(Step s) {
        Map<String, Object> body = new HashMap<>();
        body.put("goalId", s.goalId());
        body.put("phaseId", s.phaseId());
        body.put("title", s.title());
        body.put("orderIndex", s.orderIndex());
        body.put("kind", s.kind() != null ? s.kind().name() : null);
        body.put("done", s.done());
        body.put("doneAt", s.doneAt());
        body.put("manualOverride", s.manualOverride());
        body.put("metric", metricToMap(s.metric()));
        return body;
    }

    private static Map<String, Object> metricToMap(StepMetricBinding m) {
        if (m == null) return null;
        Map<String, Object> map = new HashMap<>();
        map.put("metricKey", m.metricKey());
        map.put("comparator", m.comparator() != null ? m.comparator().name() : null);
        map.put("targetValue", m.targetValue());
        map.put("windowDays", m.windowDays());
        map.put("countFrom", m.countFrom());
        return map;
    }

    private static Step toStep(String goalId, String phaseId, DocumentSnapshot snapshot) {
        String kind = snapshot.getString("kind");
        Boolean done = snapshot.getBoolean("done");
        Boolean manualOverride = snapshot.getBoolean("manualOverride");
        Long orderIndex = snapshot.getLong("orderIndex");
        return new Step(
            goalId,
            phaseId,
            snapshot.getId(),
            snapshot.getString("title"),
            orderIndex != null ? orderIndex.intValue() : 0,
            kind != null ? StepKind.valueOf(kind) : null,
            done != null && done,
            toInstant(snapshot.get("doneAt")),
            manualOverride != null && manualOverride,
            metricFromMap(snapshot.get("metric"))
        );
    }

    @SuppressWarnings("unchecked")
    private static StepMetricBinding metricFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> map = (Map<String, Object>) m;
        Object metricKey = map.get("metricKey");
        if (metricKey == null) return null;
        Object comparator = map.get("comparator");
        Object targetValue = map.get("targetValue");
        Object windowDays = map.get("windowDays");
        return new StepMetricBinding(
            metricKey.toString(),
            comparator != null ? Comparator.valueOf(comparator.toString()) : null,
            targetValue instanceof Number n ? n.doubleValue() : 0.0,
            windowDays instanceof Number wn ? wn.intValue() : null,
            toInstant(map.get("countFrom"))
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
