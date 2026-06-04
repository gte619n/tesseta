package com.gte619n.healthfitness.core.equipment;

import java.util.List;

/**
 * Parses raw, human-authored equipment text into structured
 * {@link ParsedEquipment} records. Implementations live in higher
 * modules (e.g. {@code integrations}) so {@code core} can depend
 * only on this abstraction.
 */
public interface EquipmentParser {
    List<ParsedEquipment> parse(String rawText);
}
