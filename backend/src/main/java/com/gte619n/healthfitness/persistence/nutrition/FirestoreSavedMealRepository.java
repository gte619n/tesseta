package com.gte619n.healthfitness.persistence.nutrition;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;
import static com.gte619n.healthfitness.persistence.FirestoreSupport.await;

import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.gte619n.healthfitness.core.nutrition.CompositeIngredient;
import com.gte619n.healthfitness.core.nutrition.FoodImageStatus;
import com.gte619n.healthfitness.core.nutrition.FoodSource;
import com.gte619n.healthfitness.core.nutrition.Macros;
import com.gte619n.healthfitness.core.nutrition.SavedMeal;
import com.gte619n.healthfitness.core.nutrition.SavedMealRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Firestore-backed saved-meal catalog.
// TOP-LEVEL collection mealCatalog/{mealId} — shared across all users.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class FirestoreSavedMealRepository implements SavedMealRepository {

    private static final String COLLECTION = "mealCatalog";

    private final Firestore firestore;

    public FirestoreSavedMealRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<SavedMeal> findById(String mealId) {
        DocumentSnapshot snapshot = await(collection().document(mealId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toMeal(snapshot));
    }

    @Override
    public List<SavedMeal> searchByNamePrefix(String prefixLower, int limit) {
        // Same Firestore range trick as the food catalog: startAt(prefix) ..
        // endAt(prefix + high unicode sentinel) matches the nameLower prefix.
        List<QueryDocumentSnapshot> docs = await(collection()
            .orderBy("nameLower")
            .startAt(prefixLower)
            .endAt(prefixLower + "\uf8ff")
            .limit(limit)
            .get()).getDocuments();
        return docs.stream().map(FirestoreSavedMealRepository::toMeal).toList();
    }

    @Override
    public void save(SavedMeal meal) {
        DocumentReference docRef = collection().document(meal.mealId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(meal, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public List<SavedMeal> findByImageStatus(FoodImageStatus status, int limit) {
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereEqualTo("imageStatus", status.name())
            .limit(limit)
            .get()).getDocuments();
        return docs.stream().map(FirestoreSavedMealRepository::toMeal).toList();
    }

    private CollectionReference collection() {
        return firestore.collection(COLLECTION);
    }

    private static Map<String, Object> toBody(SavedMeal m, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", m.name());
        body.put("nameLower", m.nameLower());
        body.put("createdBy", m.createdBy());
        body.put("ingredients", ingredientsToList(m.ingredients()));
        body.put("totalGrams", m.totalGrams());
        body.put("macros", macrosToMap(m.macros()));
        body.put("source", m.source() != null ? m.source().name() : null);
        body.put("imageUrl", m.imageUrl());
        body.put("imageStatus", m.imageStatus() != null ? m.imageStatus().name() : null);
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static SavedMeal toMeal(DocumentSnapshot snapshot) {
        String source = snapshot.getString("source");
        String imageStatus = snapshot.getString("imageStatus");
        return new SavedMeal(
            snapshot.getId(),
            snapshot.getString("name"),
            snapshot.getString("nameLower"),
            snapshot.getString("createdBy"),
            ingredientsFromList(snapshot.get("ingredients")),
            asDouble(snapshot.get("totalGrams")),
            macrosFromMap(snapshot.get("macros")),
            source != null ? FoodSource.valueOf(source) : null,
            snapshot.getString("imageUrl"),
            imageStatus != null ? FoodImageStatus.valueOf(imageStatus) : null,
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    private static List<Map<String, Object>> ingredientsToList(List<CompositeIngredient> ingredients) {
        if (ingredients == null) return null;
        List<Map<String, Object>> out = new ArrayList<>(ingredients.size());
        for (CompositeIngredient ing : ingredients) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", ing.name());
            m.put("foodId", ing.foodId());
            m.put("macrosPer100g", macrosToMap(ing.macrosPer100g()));
            m.put("servingGrams", ing.servingGrams());
            m.put("servingLabel", ing.servingLabel());
            m.put("quantity", ing.quantity());
            m.put("macros", macrosToMap(ing.macros()));
            out.add(m);
        }
        return out;
    }

    private static List<CompositeIngredient> ingredientsFromList(Object raw) {
        if (!(raw instanceof List<?> list)) return null;
        List<CompositeIngredient> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> map)) continue;
            out.add(new CompositeIngredient(
                (String) map.get("name"),
                (String) map.get("foodId"),
                macrosFromMap(map.get("macrosPer100g")),
                asDouble(map.get("servingGrams")),
                (String) map.get("servingLabel"),
                asDouble(map.get("quantity")),
                macrosFromMap(map.get("macros"))));
        }
        return out;
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

    private static Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) return n.doubleValue();
        return null;
    }
}
