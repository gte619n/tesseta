package com.gte619n.healthfitness.api.equipment;

import com.gte619n.healthfitness.core.equipment.ParsedEquipment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record BulkImportConfirmRequest(
    @NotNull @Valid
    List<Item> items
) {
    public record Item(
        int index,
        @NotNull String action,              // "USE_MATCH" | "CREATE_NEW" | "SKIP"
        String matchedEquipmentId,           // required when action=USE_MATCH
        ParsedEquipment parsed,              // required when action=CREATE_NEW (round-tripped from preview)
        Overrides overrides                  // optional, only used on CREATE_NEW
    ) {}

    public record Overrides(String name) {}
}
