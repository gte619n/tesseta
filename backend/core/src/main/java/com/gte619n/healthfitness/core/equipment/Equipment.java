package com.gte619n.healthfitness.core.equipment;

import java.time.Instant;
import java.util.Map;

public record Equipment(
    String equipmentId,
    String name,
    String category,
    String subcategory,
    SpecSchema specSchema,
    Map<String, Object> specs,
    String imageUrl,
    ImageStatus imageStatus,
    String ownerId,
    EquipmentStatus status,
    String contributorId,
    Integer exerciseCount,
    Instant createdAt,
    Instant updatedAt,
    // When non-null, this row is an alias of the equipmentId it points to.
    // Aliases are hidden from catalog/pending listings; callers that
    // navigate via id should resolve through this pointer.
    String aliasOfEquipmentId
) {}
