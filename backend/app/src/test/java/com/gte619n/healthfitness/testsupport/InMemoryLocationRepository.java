package com.gte619n.healthfitness.testsupport;

import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fake mirroring the Firestore repo's soft-delete (tombstone)
 * semantics from IMPL-AND-20 Phase 0: {@link #delete} archives the location
 * (also flipping isActive=false for backward compat) and archived rows are
 * excluded from {@link #findByUser} even when includeInactive is requested —
 * the includeInactive flag is the domain toggle, not the sync lifecycle.
 */
public class InMemoryLocationRepository implements LocationRepository {

    private final Map<String, Map<String, Location>> storage = new ConcurrentHashMap<>();
    // userId -> set of archived (soft-deleted) location ids.
    private final Map<String, Set<String>> archived = new ConcurrentHashMap<>();

    @Override
    public Optional<Location> findById(String userId, String locationId) {
        if (isArchived(userId, locationId)) return Optional.empty();
        return Optional.ofNullable(userMap(userId).get(locationId));
    }

    @Override
    public List<Location> findByUser(String userId, boolean includeInactive) {
        Set<String> dead = archived.getOrDefault(userId, Set.of());
        return userMap(userId).entrySet().stream()
            .filter(e -> !dead.contains(e.getKey()))
            .map(Map.Entry::getValue)
            .filter(loc -> includeInactive || loc.isActive())
            .sorted((a, b) -> a.name().compareTo(b.name()))
            .toList();
    }

    @Override
    public void save(Location location) {
        userMap(location.userId()).put(location.locationId(), location);
        Set<String> dead = archived.get(location.userId());
        if (dead != null) dead.remove(location.locationId());
    }

    @Override
    public void delete(String userId, String locationId) {
        Location existing = userMap(userId).get(locationId);
        if (existing != null) {
            // Tombstone the row (sync lifecycle) and flip isActive=false to
            // match the Firestore impl.
            archived.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(locationId);
            Location updated = new Location(
                existing.userId(),
                existing.locationId(),
                existing.name(),
                existing.address(),
                existing.coverPhotoUrl(),
                existing.is24Hours(),
                existing.hours(),
                existing.amenities(),
                existing.equipmentIds(),
                existing.equipmentSpecs(),
                existing.isDefault(),
                false, // isActive = false
                existing.createdAt(),
                Instant.now()
            );
            userMap(userId).put(locationId, updated);
        }
    }

    private boolean isArchived(String userId, String locationId) {
        return archived.getOrDefault(userId, Set.of()).contains(locationId);
    }

    @Override
    public void setDefault(String userId, String locationId) {
        Map<String, Location> userLocations = userMap(userId);

        // Clear all defaults first
        List<Location> updated = new ArrayList<>();
        for (Location loc : userLocations.values()) {
            if (loc.isDefault()) {
                updated.add(new Location(
                    loc.userId(),
                    loc.locationId(),
                    loc.name(),
                    loc.address(),
                    loc.coverPhotoUrl(),
                    loc.is24Hours(),
                    loc.hours(),
                    loc.amenities(),
                    loc.equipmentIds(),
                    loc.equipmentSpecs(),
                    false, // isDefault = false
                    loc.isActive(),
                    loc.createdAt(),
                    Instant.now()
                ));
            }
        }
        for (Location loc : updated) {
            userLocations.put(loc.locationId(), loc);
        }

        // Set the new default
        Location target = userLocations.get(locationId);
        if (target != null) {
            Location newDefault = new Location(
                target.userId(),
                target.locationId(),
                target.name(),
                target.address(),
                target.coverPhotoUrl(),
                target.is24Hours(),
                target.hours(),
                target.amenities(),
                target.equipmentIds(),
                target.equipmentSpecs(),
                true, // isDefault = true
                target.isActive(),
                target.createdAt(),
                Instant.now()
            );
            userLocations.put(locationId, newDefault);
        }
    }

    @Override
    public List<Location> findAllReferencing(String equipmentId) {
        if (equipmentId == null) return List.of();
        List<Location> matches = new ArrayList<>();
        for (Map<String, Location> userLocs : storage.values()) {
            for (Location loc : userLocs.values()) {
                List<String> ids = loc.equipmentIds();
                if (ids != null && ids.contains(equipmentId)) {
                    matches.add(loc);
                }
            }
        }
        return matches;
    }

    private Map<String, Location> userMap(String userId) {
        return storage.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
    }

    public void clear() {
        storage.clear();
        archived.clear();
    }
}
