package com.gte619n.healthfitness.core.adhoc;

import java.util.List;

/**
 * The equipment a given ad-hoc workout was generated for (IMPL-ADHOC-01 D4/D11).
 *
 * <p>{@code presetId} names a quick-pick preset ("hotel-gym", "home",
 * "bodyweight", "full-gym") when one was chosen; {@code label} is the
 * human-readable descriptor shown on the card; {@code equipmentIds} is the
 * concrete, resolved catalog-equipment set the constraint validator checks
 * exercises against; {@code freeText} preserves what the user typed. All fields
 * nullable/empty-tolerant — a pure bodyweight workout carries an empty
 * {@code equipmentIds}.
 */
public record EquipmentContext(
    String presetId,
    String label,
    List<String> equipmentIds,
    String freeText
) {
    public EquipmentContext {
        equipmentIds = equipmentIds == null ? List.of() : List.copyOf(equipmentIds);
    }

    public static EquipmentContext empty() {
        return new EquipmentContext(null, null, List.of(), null);
    }
}
