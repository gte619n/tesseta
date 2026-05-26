package com.gte619n.healthfitness.location;

import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.HoursSlot;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import com.gte619n.healthfitness.integrations.location.LocationPhotoStorage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

// Business logic for managing gym locations. Coordinates between repository
// and storage service for photo operations.
@Service
public class LocationService {

    private final LocationRepository repository;
    private final LocationPhotoStorage photoStorage;

    public LocationService(
        LocationRepository repository,
        LocationPhotoStorage photoStorage
    ) {
        this.repository = repository;
        this.photoStorage = photoStorage;
    }

    public Location create(
        String userId,
        String name,
        String address,
        boolean is24Hours,
        Map<DayOfWeek, HoursSlot> hours,
        List<String> amenities,
        List<String> equipmentIds
    ) {
        String locationId = "loc_" + UUID.randomUUID().toString().substring(0, 12);
        Instant now = Instant.now();

        Location location = new Location(
            userId,
            locationId,
            name,
            address,
            null, // coverPhotoUrl - set separately via photo upload
            is24Hours,
            hours,
            amenities,
            equipmentIds,
            Map.of(), // equipmentSpecs starts empty; falls back to catalog defaults
            false, // isDefault - set separately
            true,  // isActive - new locations are active by default
            now,
            now
        );

        repository.save(location);
        return location;
    }

    public Location update(
        String userId,
        String locationId,
        String name,
        String address,
        Boolean is24Hours,
        Map<DayOfWeek, HoursSlot> hours,
        List<String> amenities,
        List<String> equipmentIds
    ) {
        Location existing = repository.findById(userId, locationId)
            .orElseThrow(() -> new IllegalArgumentException("Location not found"));

        // Only update fields that are non-null in the request
        Location updated = new Location(
            existing.userId(),
            existing.locationId(),
            name != null ? name : existing.name(),
            address != null ? address : existing.address(),
            existing.coverPhotoUrl(),
            is24Hours != null ? is24Hours : existing.is24Hours(),
            hours != null ? hours : existing.hours(),
            amenities != null ? amenities : existing.amenities(),
            equipmentIds != null ? equipmentIds : existing.equipmentIds(),
            existing.equipmentSpecs(),
            existing.isDefault(),
            existing.isActive(),
            existing.createdAt(),
            Instant.now()
        );

        repository.save(updated);
        return updated;
    }

    /**
     * Append a single equipment ID to a location's equipment list (dedupes if
     * already present, preserving original order). Used by the bulk-import
     * confirm endpoint so each successfully created/matched piece of
     * equipment is wired into the location it was imported for.
     */
    public Location addEquipmentToLocation(
        String userId,
        String locationId,
        String equipmentId
    ) {
        Location existing = repository.findById(userId, locationId)
            .orElseThrow(() -> new IllegalArgumentException("Location not found"));

        List<String> currentIds = existing.equipmentIds() != null
            ? existing.equipmentIds()
            : List.of();
        if (currentIds.contains(equipmentId)) {
            // Already linked — no-op.
            return existing;
        }

        List<String> updatedIds = new ArrayList<>(currentIds);
        updatedIds.add(equipmentId);

        Location updated = new Location(
            existing.userId(),
            existing.locationId(),
            existing.name(),
            existing.address(),
            existing.coverPhotoUrl(),
            existing.is24Hours(),
            existing.hours(),
            existing.amenities(),
            updatedIds,
            existing.equipmentSpecs(),
            existing.isDefault(),
            existing.isActive(),
            existing.createdAt(),
            Instant.now()
        );

        repository.save(updated);
        return updated;
    }

    /**
     * Set the per-location spec overrides for a single equipment at this
     * location. Pass an empty/null map to clear overrides (catalog defaults
     * will be used). The equipment must already be on the location's
     * equipmentIds list.
     */
    public Location updateEquipmentSpecs(
        String userId,
        String locationId,
        String equipmentId,
        Map<String, Object> specs
    ) {
        Location existing = repository.findById(userId, locationId)
            .orElseThrow(() -> new IllegalArgumentException("Location not found"));

        List<String> currentIds = existing.equipmentIds() == null ? List.of() : existing.equipmentIds();
        if (!currentIds.contains(equipmentId)) {
            throw new IllegalArgumentException("Equipment not assigned to this location");
        }

        Map<String, Map<String, Object>> currentSpecs = existing.equipmentSpecs() == null
            ? new java.util.HashMap<>()
            : new java.util.HashMap<>(existing.equipmentSpecs());

        if (specs == null || specs.isEmpty()) {
            currentSpecs.remove(equipmentId);
        } else {
            currentSpecs.put(equipmentId, specs);
        }

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
            currentSpecs,
            existing.isDefault(),
            existing.isActive(),
            existing.createdAt(),
            Instant.now()
        );

        repository.save(updated);
        return updated;
    }

    public void softDelete(String userId, String locationId) {
        repository.delete(userId, locationId);
    }

    public void setDefault(String userId, String locationId) {
        repository.setDefault(userId, locationId);
    }

    public Location setCoverPhoto(String userId, String locationId, byte[] imageBytes) {
        Location existing = repository.findById(userId, locationId)
            .orElseThrow(() -> new IllegalArgumentException("Location not found"));

        // Delete old photo if it exists
        if (existing.coverPhotoUrl() != null) {
            photoStorage.delete(locationId);
        }

        // Upload new photo
        String photoUrl = photoStorage.upload(locationId, imageBytes);

        Location updated = new Location(
            existing.userId(),
            existing.locationId(),
            existing.name(),
            existing.address(),
            photoUrl,
            existing.is24Hours(),
            existing.hours(),
            existing.amenities(),
            existing.equipmentIds(),
            existing.equipmentSpecs(),
            existing.isDefault(),
            existing.isActive(),
            existing.createdAt(),
            Instant.now()
        );

        repository.save(updated);
        return updated;
    }

    public Location removeCoverPhoto(String userId, String locationId) {
        Location existing = repository.findById(userId, locationId)
            .orElseThrow(() -> new IllegalArgumentException("Location not found"));

        // Delete from GCS if exists
        if (existing.coverPhotoUrl() != null) {
            photoStorage.delete(locationId);
        }

        Location updated = new Location(
            existing.userId(),
            existing.locationId(),
            existing.name(),
            existing.address(),
            null, // Remove photo URL
            existing.is24Hours(),
            existing.hours(),
            existing.amenities(),
            existing.equipmentIds(),
            existing.equipmentSpecs(),
            existing.isDefault(),
            existing.isActive(),
            existing.createdAt(),
            Instant.now()
        );

        repository.save(updated);
        return updated;
    }
}
