package com.gte619n.healthfitness.core.equipment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EquipmentRepository {
    Optional<Equipment> findById(String equipmentId);
    List<Equipment> findByIds(Collection<String> equipmentIds);
    List<Equipment> findCatalog(String search, String category, String subcategory);
    List<Equipment> findByOwner(String ownerId);
    List<Equipment> findPendingReview();
    void save(Equipment equipment);
    void delete(String equipmentId);
}
