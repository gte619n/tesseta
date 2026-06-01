package com.gte619n.healthfitness.persistence.nutrition;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.nutrition.CatalogFood;
import com.gte619n.healthfitness.core.nutrition.FoodCatalogRepository;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.FoodStatus;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.ServingSize;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Firestore-backed food catalog repository.
// TOP-LEVEL collection foodCatalog/{foodId} — shared across all users.
// Confirmations live at foodCatalog/{foodId}/confirmations/{userId}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreFoodCatalogRepository implements FoodCatalogRepository {

    private static final String COLLECTION = "foodCatalog";
    private static final String CONFIRMATIONS = "confirmations";

    private final Firestore firestore;

    public FirestoreFoodCatalogRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<CatalogFood> findById(String foodId) {
        DocumentSnapshot snapshot = await(collection().document(foodId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toFood(snapshot));
    }

    @Override
    public List<CatalogFood> searchByNamePrefix(String prefixLower, int limit) {
        // Firestore range trick: startAt(prefix) .. endAt(prefix + high
        // unicode sentinel) returns all docs whose nameLower begins with prefix.
        List<QueryDocumentSnapshot> docs = await(collection()
            .orderBy("nameLower")
            .startAt(prefixLower)
            .endAt(prefixLower + "\uf8ff")
            .limit(limit)
            .get()).getDocuments();
        return docs.stream().map(FirestoreFoodCatalogRepository::toFood).toList();
    }

    @Override
    public Optional<CatalogFood> findByBarcode(String code) {
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereEqualTo("barcode", code)
            .limit(1)
            .get()).getDocuments();
        if (docs.isEmpty()) return Optional.empty();
        return Optional.of(toFood(docs.get(0)));
    }

    @Override
    public List<CatalogFood> findByImageStatus(FoodImageStatus status, int limit) {
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereEqualTo("imageStatus", status.name())
            .limit(limit)
            .get()).getDocuments();
        return docs.stream().map(FirestoreFoodCatalogRepository::toFood).toList();
    }

    @Override
    public void save(CatalogFood food) {
        DocumentReference docRef = collection().document(food.foodId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(food, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void saveConfirmation(String foodId, String userId) {
        // One doc per distinct user; re-confirming overwrites the same doc, so
        // the operation is idempotent and the count stays distinct-by-user.
        Map<String, Object> body = new HashMap<>();
        body.put("userId", userId);
        body.put("confirmedAt", serverTimestamp());
        await(collection().document(foodId).collection(CONFIRMATIONS)
            .document(userId).set(body, SetOptions.merge()));
    }

    @Override
    public int countConfirmations(String foodId) {
        return await(collection().document(foodId).collection(CONFIRMATIONS).get())
            .getDocuments().size();
    }

    private CollectionReference collection() {
        return firestore.collection(COLLECTION);
    }

    private static Map<String, Object> toBody(CatalogFood f, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", f.name());
        body.put("nameLower", f.nameLower());
        body.put("brand", f.brand());
        body.put("barcode", f.barcode());
        body.put("category", f.category());
        body.put("basis", "PER_100G");
        body.put("macrosPer100g", macrosToMap(f.macrosPer100g()));
        body.put("servingSizes", servingsToList(f.servingSizes()));
        body.put("defaultServingIndex", f.defaultServingIndex());
        body.put("source", f.source() != null ? f.source().name() : null);
        body.put("sourceRef", f.sourceRef());
        body.put("status", f.status() != null ? f.status().name() : null);
        body.put("confirmationCount", f.confirmationCount());
        body.put("verifiedAt", f.verifiedAt());
        body.put("imageUrl", f.imageUrl());
        body.put("imageStatus", f.imageStatus() != null ? f.imageStatus().name() : null);
        body.put("createdBy", f.createdBy());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static CatalogFood toFood(DocumentSnapshot snapshot) {
        String source = snapshot.getString("source");
        String status = snapshot.getString("status");
        String imageStatus = snapshot.getString("imageStatus");
        Long defaultServingIndex = snapshot.getLong("defaultServingIndex");
        Long confirmationCount = snapshot.getLong("confirmationCount");
        return new CatalogFood(
            snapshot.getId(),
            snapshot.getString("name"),
            snapshot.getString("nameLower"),
            snapshot.getString("brand"),
            snapshot.getString("barcode"),
            snapshot.getString("category"),
            macrosFromMap(snapshot.get("macrosPer100g")),
            servingsFromList(snapshot.get("servingSizes")),
            defaultServingIndex != null ? defaultServingIndex.intValue() : 0,
            source != null ? FoodSource.valueOf(source) : null,
            snapshot.getString("sourceRef"),
            status != null ? FoodStatus.valueOf(status) : null,
            confirmationCount != null ? confirmationCount.intValue() : 0,
            toInstant(snapshot.get("verifiedAt")),
            snapshot.getString("imageUrl"),
            imageStatus != null ? FoodImageStatus.valueOf(imageStatus) : null,
            snapshot.getString("createdBy"),
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    private static Map<String, Object> macrosToMap(Macros m) {
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

    private static Macros macrosFromMap(Object raw) {
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

    private static List<Map<String, Object>> servingsToList(List<ServingSize> servings) {
        if (servings == null) return List.of();
        List<Map<String, Object>> list = new ArrayList<>();
        for (ServingSize s : servings) {
            Map<String, Object> map = new HashMap<>();
            map.put("label", s.label());
            map.put("grams", s.grams());
            list.add(map);
        }
        return list;
    }

    private static List<ServingSize> servingsFromList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        List<ServingSize> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Object label = map.get("label");
                result.add(new ServingSize(
                    label != null ? label.toString() : null,
                    asDouble(map.get("grams"))
                ));
            }
        }
        return result;
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
