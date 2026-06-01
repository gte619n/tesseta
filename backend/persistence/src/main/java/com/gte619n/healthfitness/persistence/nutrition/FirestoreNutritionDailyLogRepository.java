package com.gte619n.healthfitness.persistence.nutrition;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.nutrition.NutritionDailyLog;
import com.gte619n.healthfitness.core.nutrition.NutritionDailyLogRepository;
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

// Firestore-backed nutrition daily log repository.
// Documents live at users/{userId}/nutritionDailyLogs/{yyyy-MM-dd}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreNutritionDailyLogRepository implements NutritionDailyLogRepository {

    private static final String SUBCOLLECTION = "nutritionDailyLogs";

    private final Firestore firestore;

    public FirestoreNutritionDailyLogRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<NutritionDailyLog> findByDate(String userId, LocalDate date) {
        DocumentSnapshot snapshot = await(collection(userId).document(date.toString()).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toLog(userId, snapshot));
    }

    @Override
    public List<NutritionDailyLog> findByDateRange(String userId, LocalDate from, LocalDate to) {
        List<QueryDocumentSnapshot> docs = await(collection(userId)
            .orderBy("date", Query.Direction.ASCENDING)
            .whereGreaterThanOrEqualTo("date", from.toString())
            .whereLessThanOrEqualTo("date", to.toString())
            .get()).getDocuments();
        return docs.stream().map(d -> toLog(userId, d)).toList();
    }

    @Override
    public void save(NutritionDailyLog log) {
        DocumentReference docRef = collection(log.userId()).document(log.date().toString());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(log, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(NutritionDailyLog log, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", log.date().toString());
        body.put("proteinGrams", log.proteinGrams());
        body.put("carbsGrams", log.carbsGrams());
        body.put("fatGrams", log.fatGrams());
        body.put("fiberGrams", log.fiberGrams());
        body.put("sugarGrams", log.sugarGrams());
        body.put("caloriesKcal", log.caloriesKcal());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static NutritionDailyLog toLog(String userId, DocumentSnapshot snapshot) {
        return new NutritionDailyLog(
            userId,
            LocalDate.parse(snapshot.getString("date")),
            snapshot.getDouble("proteinGrams"),
            snapshot.getDouble("carbsGrams"),
            snapshot.getDouble("fatGrams"),
            snapshot.getDouble("fiberGrams"),
            snapshot.getDouble("sugarGrams"),
            snapshot.getDouble("caloriesKcal"),
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
