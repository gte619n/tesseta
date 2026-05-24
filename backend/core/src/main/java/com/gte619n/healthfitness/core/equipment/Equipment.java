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
    Instant updatedAt
) {}
