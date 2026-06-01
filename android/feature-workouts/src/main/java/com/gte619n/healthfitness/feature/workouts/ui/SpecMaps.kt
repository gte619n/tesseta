package com.gte619n.healthfitness.feature.workouts.ui

import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag

/** Flattens a typed [EquipmentSpec] into the free-form map used by the form. */
fun EquipmentSpec.toSpecMap(): Map<String, Any?> = when (this) {
    is EquipmentSpec.Selectorized -> mapOf(
        "minWeight" to minWeight, "maxWeight" to maxWeight, "increment" to increment,
    )
    is EquipmentSpec.PlateLoaded -> mapOf(
        "barWeight" to barWeight, "availablePlates" to availablePlates,
    )
    EquipmentSpec.Bodyweight -> emptyMap()
    is EquipmentSpec.Cable -> mapOf(
        "weightStack" to weightStack, "numStations" to numStations,
    )
    is EquipmentSpec.Cardio -> mapOf(
        "resistanceLevels" to resistanceLevels, "hasIncline" to hasIncline,
    )
    is EquipmentSpec.WeightSet -> buildMap {
        minWeight?.let { put("minWeight", it) }
        maxWeight?.let { put("maxWeight", it) }
        increment?.let { put("increment", it) }
        weights?.let { put("weights", it) }
    }
}

private fun Map<String, Any?>.dbl(key: String): Double =
    (this[key] as? Number)?.toDouble() ?: 0.0

private fun Map<String, Any?>.dblOrNull(key: String): Double? =
    (this[key] as? Number)?.toDouble()

private fun Map<String, Any?>.int(key: String): Int =
    (this[key] as? Number)?.toInt() ?: 0

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.dblList(key: String): List<Double> =
    (this[key] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList()

/** Builds a typed [EquipmentSpec] from a form map for the given [schema] (submit-new). */
fun specFromMap(schema: SpecSchemaTag, specs: Map<String, Any?>): EquipmentSpec = when (schema) {
    SpecSchemaTag.SELECTORIZED -> EquipmentSpec.Selectorized(
        minWeight = specs.dbl("minWeight"),
        maxWeight = specs.dbl("maxWeight"),
        increment = specs.dbl("increment"),
    )
    SpecSchemaTag.PLATE_LOADED -> EquipmentSpec.PlateLoaded(
        barWeight = specs.dbl("barWeight"),
        availablePlates = specs.dblList("availablePlates"),
    )
    SpecSchemaTag.BODYWEIGHT -> EquipmentSpec.Bodyweight
    SpecSchemaTag.CABLE -> EquipmentSpec.Cable(
        weightStack = specs.dbl("weightStack"),
        numStations = specs.int("numStations"),
    )
    SpecSchemaTag.CARDIO -> EquipmentSpec.Cardio(
        resistanceLevels = specs.int("resistanceLevels"),
        hasIncline = specs["hasIncline"] as? Boolean ?: false,
    )
    SpecSchemaTag.WEIGHT_SET -> EquipmentSpec.WeightSet(
        minWeight = specs.dblOrNull("minWeight"),
        maxWeight = specs.dblOrNull("maxWeight"),
        increment = specs.dblOrNull("increment"),
        weights = specs.dblList("weights").ifEmpty { null },
    )
}
