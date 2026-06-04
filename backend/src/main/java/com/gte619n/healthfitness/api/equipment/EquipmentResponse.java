package com.gte619n.healthfitness.api.equipment;

import com.gte619n.healthfitness.core.equipment.Equipment;
import com.gte619n.healthfitness.core.equipment.EquipmentStatus;
import com.gte619n.healthfitness.core.equipment.ImageStatus;
import com.gte619n.healthfitness.core.equipment.SpecSchema;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EquipmentResponse(
    String equipmentId,
    String name,
    String category,
    String subcategory,
    SpecSchema specSchema,
    Map<String, Object> specs,
    String imageUrl,
    List<String> imageCandidates,
    ImageStatus imageStatus,
    String ownerId,
    EquipmentStatus status,
    String contributorId,
    Integer exerciseCount,
    Instant createdAt,
    Instant updatedAt
) {
    public static EquipmentResponse from(Equipment equipment) {
        return new EquipmentResponse(
            equipment.equipmentId(),
            equipment.name(),
            equipment.category(),
            equipment.subcategory(),
            equipment.specSchema(),
            equipment.specs(),
            equipment.imageUrl(),
            equipment.imageCandidates(),
            equipment.imageStatus(),
            equipment.ownerId(),
            equipment.status(),
            equipment.contributorId(),
            equipment.exerciseCount(),
            equipment.createdAt(),
            equipment.updatedAt()
        );
    }
}
