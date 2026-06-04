package com.gte619n.healthfitness.api.equipment;

import com.gte619n.healthfitness.core.equipment.SpecSchema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CreateEquipmentRequest(
    @NotBlank(message = "name is required")
    String name,

    @NotBlank(message = "category is required")
    String category,

    @NotBlank(message = "subcategory is required")
    String subcategory,

    @NotNull(message = "specSchema is required")
    SpecSchema specSchema,

    Map<String, Object> specs
) {}
