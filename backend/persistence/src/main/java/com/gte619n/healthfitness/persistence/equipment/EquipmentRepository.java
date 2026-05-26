package com.gte619n.healthfitness.persistence.equipment;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Firestore-backed equipment repository.
// Documents live at the top-level equipment/{equipmentId} collection.
// Equipment with ownerId == null belongs to the system catalog.
// Equipment with ownerId set is user-owned custom entry.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class EquipmentRepository implements com.gte619n.healthfitness.core.equipment.EquipmentRepository {

    private static final String COLLECTION = "equipment";

    private final Firestore firestore;

    public EquipmentRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<Equipment> findById(String equipmentId) {
        DocumentSnapshot snapshot = await(collection().document(equipmentId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toEquipment(snapshot));
    }

    @Override
    public List<Equipment> findByIds(Collection<String> equipmentIds) {
        if (equipmentIds == null || equipmentIds.isEmpty()) {
            return List.of();
        }
        // Firestore whereIn has a limit of 30 items, so we batch
        List<Equipment> results = new ArrayList<>();
        List<String> idList = new ArrayList<>(equipmentIds);
        for (int i = 0; i < idList.size(); i += 30) {
            List<String> batch = idList.subList(i, Math.min(i + 30, idList.size()));
            List<QueryDocumentSnapshot> docs = await(collection()
                .whereIn("__name__", batch.stream()
                    .map(id -> collection().document(id))
                    .toList())
                .get()).getDocuments();
            docs.stream().map(this::toEquipment).forEach(results::add);
        }
        return results;
    }

    @Override
    public List<Equipment> findCatalog(String search, String category, String subcategory) {
        // Start with active system catalog items (ownerId is null)
        Query query = collection()
            .whereEqualTo("status", EquipmentStatus.ACTIVE.name());

        if (category != null && !category.isBlank()) {
            query = query.whereEqualTo("category", category);
        }
        if (subcategory != null && !subcategory.isBlank()) {
            query = query.whereEqualTo("subcategory", subcategory);
        }

        List<QueryDocumentSnapshot> docs = await(query
            .orderBy("name", Query.Direction.ASCENDING)
            .limit(200)
            .get()).getDocuments();

        List<Equipment> results = docs.stream()
            .map(this::toEquipment)
            .filter(e -> e.aliasOfEquipmentId() == null)
            .toList();

        // If search is provided, filter client-side (Firestore doesn't support full-text search)
        if (search != null && !search.isBlank()) {
            String searchLower = search.toLowerCase();
            return results.stream()
                .filter(e -> e.name() != null && e.name().toLowerCase().contains(searchLower))
                .toList();
        }
        return results;
    }

    @Override
    public List<Equipment> findByOwner(String ownerId) {
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereEqualTo("ownerId", ownerId)
            .orderBy("name", Query.Direction.ASCENDING)
            .limit(500)
            .get()).getDocuments();
        return docs.stream().map(this::toEquipment).toList();
    }

    @Override
    public List<Equipment> findPendingReview() {
        List<QueryDocumentSnapshot> docs = await(collection()
            .whereEqualTo("status", EquipmentStatus.PENDING_REVIEW.name())
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .limit(100)
            .get()).getDocuments();
        return docs.stream()
            .map(this::toEquipment)
            .filter(e -> e.aliasOfEquipmentId() == null)
            .toList();
    }

    @Override
    public void save(Equipment equipment) {
        DocumentReference docRef = collection().document(equipment.equipmentId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(equipment, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String equipmentId) {
        await(collection().document(equipmentId).delete());
    }

    private CollectionReference collection() {
        return firestore.collection(COLLECTION);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toBody(Equipment eq, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", eq.name());
        body.put("category", eq.category());
        body.put("subcategory", eq.subcategory());
        body.put("specSchema", eq.specSchema() == null ? null : eq.specSchema().name());
        body.put("specs", eq.specs() == null ? new HashMap<>() : eq.specs());
        body.put("imageUrl", eq.imageUrl());
        body.put("imageStatus", eq.imageStatus() == null ? null : eq.imageStatus().name());
        body.put("ownerId", eq.ownerId());
        body.put("status", eq.status() == null ? EquipmentStatus.ACTIVE.name() : eq.status().name());
        body.put("contributorId", eq.contributorId());
        body.put("exerciseCount", eq.exerciseCount());
        body.put("aliasOfEquipmentId", eq.aliasOfEquipmentId());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    @SuppressWarnings("unchecked")
    private Equipment toEquipment(DocumentSnapshot snapshot) {
        String specSchemaStr = snapshot.getString("specSchema");
        String imageStatusStr = snapshot.getString("imageStatus");
        String statusStr = snapshot.getString("status");
        Long exerciseCountLong = snapshot.getLong("exerciseCount");

        return new Equipment(
            snapshot.getId(),
            snapshot.getString("name"),
            snapshot.getString("category"),
            snapshot.getString("subcategory"),
            specSchemaStr == null ? null : SpecSchema.valueOf(specSchemaStr),
            (Map<String, Object>) snapshot.get("specs"),
            snapshot.getString("imageUrl"),
            imageStatusStr == null ? null : ImageStatus.valueOf(imageStatusStr),
            snapshot.getString("ownerId"),
            statusStr == null ? EquipmentStatus.ACTIVE : EquipmentStatus.valueOf(statusStr),
            snapshot.getString("contributorId"),
            exerciseCountLong == null ? null : exerciseCountLong.intValue(),
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt")),
            snapshot.getString("aliasOfEquipmentId")
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
