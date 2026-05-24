package com.gte619n.healthfitness.location;

import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.HoursSlot;
import com.gte619n.healthfitness.core.location.Location;
import com.gte619n.healthfitness.core.location.LocationRepository;
import com.gte619n.healthfitness.integrations.location.LocationPhotoStorage;
import java.time.Instant;
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
            existing.isDefault(),
            existing.isActive(),
            existing.createdAt(),
            Instant.now()
        );

        repository.save(updated);
        return updated;
    }
}
