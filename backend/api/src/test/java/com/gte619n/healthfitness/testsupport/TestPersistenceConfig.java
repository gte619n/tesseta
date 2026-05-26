package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentRepository;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

// In-memory test configuration for Equipment API tests
@TestConfiguration
public class TestPersistenceConfig {

    @Bean
    EquipmentRepository equipmentRepository() {
        return new InMemoryEquipmentRepository();
    }

    @Bean
    LocationRepository locationRepository() {
        return new InMemoryLocationRepository();
    }

    static class InMemoryLocationRepository implements LocationRepository {
        private final Map<String, Map<String, Location>> byUser = new ConcurrentHashMap<>();

        @Override
        public Optional<Location> findById(String userId, String locationId) {
            return Optional.ofNullable(byUser.getOrDefault(userId, Map.of()).get(locationId));
        }

        @Override
        public List<Location> findByUser(String userId, boolean includeInactive) {
            return byUser.getOrDefault(userId, Map.of()).values().stream()
                .filter(l -> includeInactive || l.isActive())
                .toList();
        }

        @Override
        public void save(Location location) {
            byUser.computeIfAbsent(location.userId(), k -> new ConcurrentHashMap<>())
                .put(location.locationId(), location);
        }

        @Override
        public void delete(String userId, String locationId) {
            byUser.getOrDefault(userId, Map.of()).remove(locationId);
        }

        @Override
        public void setDefault(String userId, String locationId) { /* no-op for these tests */ }

        @Override
        public List<Location> findAllReferencing(String equipmentId) {
            if (equipmentId == null) return List.of();
            List<Location> matches = new ArrayList<>();
            for (Map<String, Location> userMap : byUser.values()) {
                for (Location loc : userMap.values()) {
                    List<String> ids = loc.equipmentIds();
                    if (ids != null && ids.contains(equipmentId)) matches.add(loc);
                }
            }
            return matches;
        }
    }

    static class InMemoryEquipmentRepository implements EquipmentRepository {
        private final Map<String, Equipment> store = new ConcurrentHashMap<>();

        @Override
        public Optional<Equipment> findById(String equipmentId) {
            return Optional.ofNullable(store.get(equipmentId));
        }

        @Override
        public List<Equipment> findByIds(Collection<String> equipmentIds) {
            return equipmentIds.stream()
                .map(store::get)
                .filter(java.util.Objects::nonNull)
                .toList();
        }

        @Override
        public List<Equipment> findCatalog(String search, String category, String subcategory) {
            return store.values().stream()
                .filter(e -> e.ownerId() == null) // Catalog items have no owner
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
    }
}
