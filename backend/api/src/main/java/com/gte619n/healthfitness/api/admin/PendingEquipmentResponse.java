package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.core.equipment.SpecSchema;
import java.time.Instant;
import java.util.Map;

public record PendingEquipmentResponse(
    String equipmentId,
    String name,
    String category,
    String subcategory,
    SpecSchema specSchema,
    Map<String, Object> specs,
    String contributorId,
    String contributorEmail,  // Lookup from user service or display as contributorId for now
    Instant submittedAt
) {}
