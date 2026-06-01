package com.gte619n.healthfitness.feature.workouts

import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.gte619n.healthfitness.domain.workouts.EquipmentStatus
import com.gte619n.healthfitness.domain.workouts.ImageStatus
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import java.time.Instant

object Fixtures {
    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")

    fun location(
        id: String = "gym-1",
        name: String = "Home Gym",
        isDefault: Boolean = false,
        equipmentIds: List<String> = emptyList(),
        equipmentSpecs: Map<String, Map<String, Any?>> = emptyMap(),
    ): Location = Location(
        locationId = id,
        name = name,
        address = "1 Main St",
        coverPhotoUrl = null,
        is24Hours = false,
        hours = null,
        amenities = emptyList(),
        equipmentIds = equipmentIds,
        equipmentSpecs = equipmentSpecs,
        isDefault = isDefault,
        isActive = true,
        createdAt = now,
        updatedAt = now,
    )

    fun equipment(
        id: String = "eq-1",
        name: String = "Olympic Barbell",
        schema: SpecSchemaTag = SpecSchemaTag.PLATE_LOADED,
        specs: EquipmentSpec = EquipmentSpec.PlateLoaded(45.0, listOf(2.5, 5.0, 10.0, 25.0, 45.0)),
    ): Equipment = Equipment(
        equipmentId = id,
        name = name,
        category = "Free Weights",
        subcategory = "Barbells",
        specSchema = schema,
        specs = specs,
        imageUrl = null,
        imageStatus = ImageStatus.PENDING,
        ownerId = null,
        status = EquipmentStatus.ACTIVE,
        contributorId = null,
        exerciseCount = null,
        createdAt = now,
        updatedAt = now,
    )
}
