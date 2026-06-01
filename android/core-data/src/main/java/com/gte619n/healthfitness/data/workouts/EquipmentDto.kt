package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.domain.workouts.CreateEquipmentRequest
import com.gte619n.healthfitness.domain.workouts.Equipment
import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.gte619n.healthfitness.domain.workouts.EquipmentStatus
import com.gte619n.healthfitness.domain.workouts.ImageStatus
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import java.time.Instant

/**
 * Wire mirror of a catalog equipment. `specs` is decoded via the
 * polymorphic [EquipmentSpecJsonAdapter] registered on the workouts Moshi.
 * Enum-ish fields stay as Strings on the wire and are mapped defensively
 * (unknown values degrade to a safe default).
 */
data class EquipmentDto(
    val equipmentId: String,
    val name: String,
    val category: String = "",
    val subcategory: String = "",
    val specSchema: String = "BODYWEIGHT",
    val specs: EquipmentSpec = EquipmentSpec.Bodyweight,
    val imageUrl: String? = null,
    val imageStatus: String = "PENDING",
    val ownerId: String? = null,
    val status: String = "ACTIVE",
    val contributorId: String? = null,
    val exerciseCount: Int? = null,
    val createdAt: String,
    val updatedAt: String,
)

data class CreateEquipmentDto(
    val name: String,
    val category: String,
    val subcategory: String,
    val specSchema: String,
    val specs: EquipmentSpec,
)

// ---- Mappers ----

private fun specSchemaOf(raw: String): SpecSchemaTag =
    SpecSchemaTag.entries.firstOrNull { it.name == raw.uppercase() } ?: SpecSchemaTag.BODYWEIGHT

private fun imageStatusOf(raw: String): ImageStatus =
    ImageStatus.entries.firstOrNull { it.name == raw.uppercase() } ?: ImageStatus.PENDING

private fun equipmentStatusOf(raw: String): EquipmentStatus =
    EquipmentStatus.entries.firstOrNull { it.name == raw.uppercase() } ?: EquipmentStatus.ACTIVE

fun EquipmentDto.toDomain(): Equipment = Equipment(
    equipmentId = equipmentId,
    name = name,
    category = category,
    subcategory = subcategory,
    specSchema = specSchemaOf(specSchema),
    specs = specs,
    imageUrl = imageUrl,
    imageStatus = imageStatusOf(imageStatus),
    ownerId = ownerId,
    status = equipmentStatusOf(status),
    contributorId = contributorId,
    exerciseCount = exerciseCount,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

fun CreateEquipmentRequest.toDto(): CreateEquipmentDto = CreateEquipmentDto(
    name = name,
    category = category,
    subcategory = subcategory,
    specSchema = specSchema.name,
    specs = specs,
)
