package com.gte619n.healthfitness.domain.workouts

import java.time.Instant

/** Discriminator for [EquipmentSpec]; wire form is the uppercase enum name. */
enum class SpecSchemaTag { SELECTORIZED, PLATE_LOADED, BODYWEIGHT, CABLE, CARDIO, WEIGHT_SET }

enum class ImageStatus { PENDING, GENERATED, FAILED }

enum class EquipmentStatus { ACTIVE, PENDING_REVIEW, REJECTED }

/**
 * Polymorphic equipment specifications. The wire discriminator field is
 * `specSchema` (uppercase, e.g. "SELECTORIZED"). Unknown schemas degrade to
 * [Bodyweight] so the catalog keeps rendering.
 */
sealed class EquipmentSpec {
    abstract val tag: SpecSchemaTag

    data class Selectorized(
        val minWeight: Double,
        val maxWeight: Double,
        val increment: Double,
    ) : EquipmentSpec() {
        override val tag = SpecSchemaTag.SELECTORIZED
    }

    data class PlateLoaded(
        val barWeight: Double,
        val availablePlates: List<Double>,
    ) : EquipmentSpec() {
        override val tag = SpecSchemaTag.PLATE_LOADED
    }

    data object Bodyweight : EquipmentSpec() {
        override val tag = SpecSchemaTag.BODYWEIGHT
    }

    data class Cable(
        val weightStack: Double,
        val numStations: Int,
    ) : EquipmentSpec() {
        override val tag = SpecSchemaTag.CABLE
    }

    data class Cardio(
        val resistanceLevels: Int,
        val hasIncline: Boolean,
    ) : EquipmentSpec() {
        override val tag = SpecSchemaTag.CARDIO
    }

    data class WeightSet(
        val minWeight: Double?,
        val maxWeight: Double?,
        val increment: Double?,
        val weights: List<Double>?,
    ) : EquipmentSpec() {
        override val tag = SpecSchemaTag.WEIGHT_SET
    }
}

data class Equipment(
    val equipmentId: String,
    val name: String,
    val category: String,
    val subcategory: String,
    val specSchema: SpecSchemaTag,
    val specs: EquipmentSpec,
    val imageUrl: String?,
    val imageStatus: ImageStatus,
    val ownerId: String?,
    val status: EquipmentStatus,
    val contributorId: String?,
    val exerciseCount: Int?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** A per-location override of an equipment's catalog specs (free-form map). */
data class EquipmentOverride(
    val equipmentId: String,
    val specs: Map<String, Any?>,
)
