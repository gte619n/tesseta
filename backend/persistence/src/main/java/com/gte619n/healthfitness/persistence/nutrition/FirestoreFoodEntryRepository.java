package com.gte619n.healthfitness.persistence.nutrition;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.nutrition.EntrySource;
import com.gte619n.healthfitness.core.nutrition.FoodEntry;
import com.gte619n.healthfitness.core.nutrition.FoodEntryRepository;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.MealType;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
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

// Firestore-backed food entry repository.
// Documents live at users/{userId}/nutritionDays/{yyyy-MM-dd}/entries/{entryId}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreFoodEntryRepository implements FoodEntryRepository {

    private static final String DAYS = "nutritionDays";
    private static final String ENTRIES = "entries";

    private final Firestore firestore;

    public FirestoreFoodEntryRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public List<FoodEntry> findByDate(String userId, LocalDate date) {
        List<QueryDocumentSnapshot> docs = await(entries(userId, date).get()).getDocuments();
        return docs.stream().map(d -> toEntry(userId, date, d)).toList();
    }

    @Override
    public Optional<FoodEntry> findById(String userId, LocalDate date, String entryId) {
        DocumentSnapshot snapshot = await(entries(userId, date).document(entryId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toEntry(userId, date, snapshot));
    }

    @Override
    public void save(FoodEntry entry) {
        DocumentReference docRef = entries(entry.userId(), entry.date()).document(entry.entryId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(entry, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String userId, LocalDate date, String entryId) {
        await(entries(userId, date).document(entryId).delete());
    }

    private CollectionReference entries(String userId, LocalDate date) {
        return firestore.collection("users").document(userId)
            .collection(DAYS).document(date.toString())
            .collection(ENTRIES);
    }

    private static Map<String, Object> toBody(FoodEntry e, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("date", e.date().toString());
        body.put("meal", e.meal() != null ? e.meal().name() : null);
        body.put("foodId", e.foodId());
        body.put("foodName", e.foodName());
        body.put("servingLabel", e.servingLabel());
        body.put("servingGrams", e.servingGrams());
        body.put("quantity", e.quantity());
        body.put("macros", macrosToMap(e.macros()));
        body.put("photoRef", e.photoRef());
        body.put("source", e.source() != null ? e.source().name() : null);
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static FoodEntry toEntry(String userId, LocalDate date, DocumentSnapshot snapshot) {
        String meal = snapshot.getString("meal");
        String source = snapshot.getString("source");
        return new FoodEntry(
            userId,
            date,
            snapshot.getId(),
            meal != null ? MealType.valueOf(meal) : null,
            snapshot.getString("foodId"),
            snapshot.getString("foodName"),
            snapshot.getString("servingLabel"),
            snapshot.getDouble("servingGrams"),
            snapshot.getDouble("quantity"),
            macrosFromMap(snapshot.get("macros")),
            snapshot.getString("photoRef"),
            source != null ? EntrySource.valueOf(source) : null,
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    static Map<String, Object> macrosToMap(Macros m) {
        if (m == null) return null;
        Map<String, Object> map = new HashMap<>();
        map.put("caloriesKcal", m.caloriesKcal());
        map.put("proteinGrams", m.proteinGrams());
        map.put("carbsGrams", m.carbsGrams());
        map.put("fatGrams", m.fatGrams());
        map.put("fiberGrams", m.fiberGrams());
        map.put("sugarGrams", m.sugarGrams());
        return map;
    }

    static Macros macrosFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        return new Macros(
            asDouble(map.get("caloriesKcal")),
            asDouble(map.get("proteinGrams")),
            asDouble(map.get("carbsGrams")),
            asDouble(map.get("fatGrams")),
            asDouble(map.get("fiberGrams")),
            asDouble(map.get("sugarGrams"))
        );
    }

    private static Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        return null;
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
