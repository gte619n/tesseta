package com.gte619n.healthfitness.api.location;

import com.gte619n.healthfitness.core.location.DayOfWeek;
import com.gte619n.healthfitness.core.location.HoursSlot;
import com.gte619n.healthfitness.core.location.Location;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record LocationResponse(
    String locationId,
    String name,
    String address,
    String coverPhotoUrl,
    boolean is24Hours,
    Map<DayOfWeek, HoursSlot> hours,
    List<String> amenities,
    List<String> equipmentIds,
    Map<String, Map<String, Object>> equipmentSpecs,
    boolean isDefault,
    boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {
    public static LocationResponse from(Location location) {
        return new LocationResponse(
            location.locationId(),
            location.name(),
            location.address(),
            location.coverPhotoUrl(),
            location.is24Hours(),
            location.hours(),
            location.amenities(),
            location.equipmentIds(),
            location.equipmentSpecs() == null ? Map.of() : location.equipmentSpecs(),
            location.isDefault(),
            location.isActive(),
            location.createdAt(),
            location.updatedAt()
        );
    }
}
