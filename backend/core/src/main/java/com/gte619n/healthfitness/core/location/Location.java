package com.gte619n.healthfitness.core.location;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record Location(
    String userId,
    String locationId,
    String name,
    String address,
    String coverPhotoUrl,
    boolean is24Hours,
    Map<DayOfWeek, HoursSlot> hours,
    List<String> amenities,
    List<String> equipmentIds,
    boolean isDefault,
    boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {}
