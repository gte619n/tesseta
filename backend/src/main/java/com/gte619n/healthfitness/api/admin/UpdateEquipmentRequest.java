package com.gte619n.healthfitness.api.admin;

import com.gte619n.healthfitness.core.equipment.SpecSchema;
import java.util.Map;

public record UpdateEquipmentRequest(
    String name,
    String category,
    String subcategory,
    SpecSchema specSchema,
    Map<String, Object> specs
) {}
