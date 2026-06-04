package com.gte619n.healthfitness.core.equipment;

import java.util.Map;

public record ParsedEquipment(
    String name,
    String brand,
    String category,
    String subcategory,
    SpecSchema specSchema,
    Map<String, Object> specs,
    String confidence,
    String rawText
) {}
