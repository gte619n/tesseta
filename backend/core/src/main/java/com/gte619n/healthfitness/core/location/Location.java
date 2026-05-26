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
    // Per-location spec overrides: keyed by equipmentId, value is the same
    // shape as Equipment.specs. Catalog's specs serve as defaults; this
    // map carries the gym-specific values (e.g. stack weight at this gym).
    Map<String, Map<String, Object>> equipmentSpecs,
    boolean isDefault,
    boolean isActive,
    Instant createdAt,
    Instant updatedAt
) {}
