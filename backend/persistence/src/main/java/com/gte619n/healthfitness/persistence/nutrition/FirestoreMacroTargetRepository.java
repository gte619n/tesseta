package com.gte619n.healthfitness.persistence.nutrition;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.nutrition.MacroTarget;
import com.gte619n.healthfitness.core.nutrition.MacroTargetRepository;
import com.gte619n.healthfitness.core.nutrition.Macros;
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

// Firestore-backed macro target repository.
// Documents live at users/{userId}/nutritionTargets/{targetId}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreMacroTargetRepository implements MacroTargetRepository {

    private static final String SUBCOLLECTION = "nutritionTargets";

    private final Firestore firestore;

    public FirestoreMacroTargetRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<MacroTarget> findActive(String userId) {
        String today = LocalDate.now().toString();
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .orderBy("effectiveFrom", Query.Direction.DESCENDING)
            .whereLessThanOrEqualTo("effectiveFrom", today)
            .limit(1)
            .get()).getDocuments();
        if (docs.isEmpty()) return Optional.empty();
        return Optional.of(toTarget(userId, docs.get(0)));
    }

    @Override
    public void save(MacroTarget target) {
        DocumentReference docRef = collection(target.userId()).document(target.targetId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(target, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public List<MacroTarget> findAll(String userId) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .orderBy("effectiveFrom", Query.Direction.DESCENDING)
            .get()).getDocuments();
        return docs.stream().map(d -> toTarget(userId, d)).toList();
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(MacroTarget t, boolean isNew) {
        Macros m = t.macros() != null ? t.macros() : Macros.zero();
        Map<String, Object> body = new HashMap<>();
        body.put("caloriesKcal", m.caloriesKcal());
        body.put("proteinGrams", m.proteinGrams());
        body.put("carbsGrams", m.carbsGrams());
        body.put("fatGrams", m.fatGrams());
        body.put("fiberGrams", m.fiberGrams());
        body.put("sugarGrams", m.sugarGrams());
        body.put("effectiveFrom", t.effectiveFrom() != null ? t.effectiveFrom().toString() : null);
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static MacroTarget toTarget(String userId, DocumentSnapshot snapshot) {
        Macros macros = new Macros(
            snapshot.getDouble("caloriesKcal"),
            snapshot.getDouble("proteinGrams"),
            snapshot.getDouble("carbsGrams"),
            snapshot.getDouble("fatGrams"),
            snapshot.getDouble("fiberGrams"),
            snapshot.getDouble("sugarGrams")
        );
        String effectiveFrom = snapshot.getString("effectiveFrom");
        return new MacroTarget(
            userId,
            snapshot.getId(),
            macros,
            effectiveFrom != null ? LocalDate.parse(effectiveFrom) : null,
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
