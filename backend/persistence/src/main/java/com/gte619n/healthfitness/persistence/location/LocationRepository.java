package com.gte619n.healthfitness.persistence.location;

import static com.gte619n.healthfitness.persistence.FirestoreMapper.serverTimestamp;
import static com.gte619n.healthfitness.persistence.FirestoreMapper.toInstant;

import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.HoursSlot;
import com.gte619n.healthfitness.core.location.Location;
import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.CollectionReference;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

// Firestore-backed location repository.
// Documents live at users/{userId}/locations/{locationId}.
@Repository
@ConditionalOnProperty(name = "app.persistence.firestore-enabled", havingValue = "true", matchIfMissing = true)
public class LocationRepository implements com.gte619n.healthfitness.core.location.LocationRepository {

    private static final String SUBCOLLECTION = "locations";

    private final Firestore firestore;

    public LocationRepository(Firestore firestore) {
        this.firestore = firestore;
    }

    @Override
    public Optional<Location> findById(String userId, String locationId) {
        DocumentSnapshot snapshot = await(collection(userId).document(locationId).get());
        if (!snapshot.exists()) return Optional.empty();
        return Optional.of(toLocation(userId, snapshot));
    }

    @Override
    public List<Location> findByUser(String userId, boolean includeInactive) {
        Query query = collection(userId).orderBy("name", Query.Direction.ASCENDING);
        if (!includeInactive) {
            query = query.whereEqualTo("isActive", true);
        }
        List<QueryDocumentSnapshot> docs = await(query.limit(100).get()).getDocuments();
        return docs.stream().map(d -> toLocation(userId, d)).toList();
    }

    @Override
    public void save(Location location) {
        DocumentReference docRef = collection(location.userId()).document(location.locationId());
        DocumentSnapshot existing = await(docRef.get());
        Map<String, Object> body = toBody(location, !existing.exists());
        await(docRef.set(body, SetOptions.merge()));
    }

    @Override
    public void delete(String userId, String locationId) {
        // Soft delete - set isActive to false
        DocumentReference docRef = collection(userId).document(locationId);
        Map<String, Object> updates = new HashMap<>();
        updates.put("isActive", false);
        updates.put("updatedAt", serverTimestamp());
        await(docRef.set(updates, SetOptions.merge()));
    }

    @Override
    public void setDefault(String userId, String locationId) {
        // First, unset any existing default
        List<QueryDocumentSnapshot> existingDefaults = await(collection(userId)
            .whereEqualTo("isDefault", true)
            .get()).getDocuments();

        WriteBatch batch = firestore.batch();
        for (QueryDocumentSnapshot doc : existingDefaults) {
            Map<String, Object> updates = new HashMap<>();
            updates.put("isDefault", false);
            updates.put("updatedAt", serverTimestamp());
            batch.set(doc.getReference(), updates, SetOptions.merge());
        }

        // Set the new default
        DocumentReference newDefault = collection(userId).document(locationId);
        Map<String, Object> updates = new HashMap<>();
        updates.put("isDefault", true);
        updates.put("updatedAt", serverTimestamp());
        batch.set(newDefault, updates, SetOptions.merge());

        await(batch.commit());
    }

    @Override
    public List<Location> findAllReferencing(String equipmentId) {
        if (equipmentId == null || equipmentId.isBlank()) return List.of();
        List<QueryDocumentSnapshot> docs = await(
            firestore.collectionGroup(SUBCOLLECTION)
                .whereArrayContains("equipmentIds", equipmentId)
                .get()
        ).getDocuments();
        return docs.stream()
            .map(d -> {
                String userId = d.getReference().getParent().getParent().getId();
                return toLocation(userId, d);
            })
            .toList();
    }

    private CollectionReference collection(String userId) {
        return firestore.collection("users").document(userId).collection(SUBCOLLECTION);
    }

    private static Map<String, Object> toBody(Location loc, boolean isNew) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", loc.name());
        body.put("address", loc.address());
        body.put("coverPhotoUrl", loc.coverPhotoUrl());
        body.put("is24Hours", loc.is24Hours());
        body.put("hours", hoursToMap(loc.hours()));
        body.put("amenities", loc.amenities() == null ? List.of() : loc.amenities());
        body.put("equipmentIds", loc.equipmentIds() == null ? List.of() : loc.equipmentIds());
        body.put("equipmentSpecs", loc.equipmentSpecs() == null ? Map.of() : loc.equipmentSpecs());
        body.put("isDefault", loc.isDefault());
        body.put("isActive", loc.isActive());
        body.put("updatedAt", serverTimestamp());
        if (isNew) {
            body.put("createdAt", serverTimestamp());
        }
        return body;
    }

    private static Map<String, Object> hoursToMap(Map<DayOfWeek, HoursSlot> hours) {
        if (hours == null) return null;
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<DayOfWeek, HoursSlot> entry : hours.entrySet()) {
            Map<String, Object> slot = new HashMap<>();
            slot.put("open", entry.getValue().open());
            slot.put("close", entry.getValue().close());
            result.put(entry.getKey().name(), slot);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<DayOfWeek, HoursSlot> hoursFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<DayOfWeek, HoursSlot> result = new EnumMap<>(DayOfWeek.class);
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            try {
                DayOfWeek day = DayOfWeek.valueOf(entry.getKey().toString());
                if (entry.getValue() instanceof Map<?, ?> slotMap) {
                    String open = slotMap.get("open") != null ? slotMap.get("open").toString() : null;
                    String close = slotMap.get("close") != null ? slotMap.get("close").toString() : null;
                    result.put(day, new HoursSlot(open, close));
                }
            } catch (IllegalArgumentException ignored) {
                // Skip invalid day entries
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
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

    private static Location toLocation(String userId, DocumentSnapshot snapshot) {
        Boolean is24Hours = snapshot.getBoolean("is24Hours");
        Boolean isDefault = snapshot.getBoolean("isDefault");
        Boolean isActive = snapshot.getBoolean("isActive");
        return new Location(
            userId,
            snapshot.getId(),
            snapshot.getString("name"),
            snapshot.getString("address"),
            snapshot.getString("coverPhotoUrl"),
            is24Hours != null && is24Hours,
            hoursFromMap(snapshot.get("hours")),
            toStringList(snapshot.get("amenities")),
            toStringList(snapshot.get("equipmentIds")),
            equipmentSpecsFromMap(snapshot.get("equipmentSpecs")),
            isDefault != null && isDefault,
            isActive == null || isActive, // Default to active if not set
            toInstant(snapshot.get("createdAt")),
            toInstant(snapshot.get("updatedAt"))
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> equipmentSpecsFromMap(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return Map.of();
        Map<String, Map<String, Object>> result = new HashMap<>();
        for (Map.Entry<?, ?> entry : m.entrySet()) {
            if (entry.getKey() != null && entry.getValue() instanceof Map<?, ?> specMap) {
                Map<String, Object> typed = new HashMap<>();
                for (Map.Entry<?, ?> sp : specMap.entrySet()) {
                    if (sp.getKey() != null) {
                        typed.put(sp.getKey().toString(), sp.getValue());
                    }
                }
                result.put(entry.getKey().toString(), typed);
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
