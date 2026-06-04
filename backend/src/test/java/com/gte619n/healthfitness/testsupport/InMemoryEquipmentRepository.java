package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryEquipmentRepository implements EquipmentRepository {

    private final Map<String, Equipment> store = new ConcurrentHashMap<>();

    @Override
    public Optional<Equipment> findById(String equipmentId) {
        return Optional.ofNullable(store.get(equipmentId));
    }

    @Override
    public List<Equipment> findByIds(Collection<String> equipmentIds) {
        return equipmentIds.stream()
            .map(store::get)
            .filter(Objects::nonNull)
            .toList();
    }

    @Override
    public List<Equipment> findCatalog(String search, String category, String subcategory) {
        return store.values().stream()
            .filter(e -> e.ownerId() == null)
            .filter(e -> e.status() == EquipmentStatus.ACTIVE)
            .filter(e -> search == null || e.name().toLowerCase().contains(search.toLowerCase()))
            .filter(e -> category == null || e.category().equals(category))
            .filter(e -> subcategory == null || e.subcategory().equals(subcategory))
            .toList();
    }

    @Override
    public List<Equipment> findByOwner(String ownerId) {
        return store.values().stream()
            .filter(e -> ownerId.equals(e.ownerId()))
            .toList();
    }

    @Override
    public List<Equipment> findPendingReview() {
        return store.values().stream()
            .filter(e -> e.status() == EquipmentStatus.PENDING_REVIEW)
            .toList();
    }

    @Override
    public void save(Equipment equipment) {
        store.put(equipment.equipmentId(), equipment);
    }

    @Override
    public void delete(String equipmentId) {
        store.remove(equipmentId);
    }

    public void clear() {
        store.clear();
    }
}
