package com.gte619n.healthfitness.api.adhoc;

import com.gte619n.healthfitness.core.adhoc.EquipmentContext;
import java.util.List;

/** Wire form of {@link EquipmentContext}. */
public record EquipmentContextDto(
    String presetId,
    String label,
    List<String> equipmentIds,
    String freeText
) {
    public static EquipmentContextDto from(EquipmentContext c) {
        if (c == null) {
            return null;
        }
        return new EquipmentContextDto(c.presetId(), c.label(), c.equipmentIds(), c.freeText());
    }

    public EquipmentContext toDomain() {
        return new EquipmentContext(presetId, label, equipmentIds, freeText);
    }
}
