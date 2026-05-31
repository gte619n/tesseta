package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.Amenity
import com.gte619n.healthfitness.domain.workouts.CreateLocationRequest
import com.gte619n.healthfitness.domain.workouts.HoursSlot
import com.gte619n.healthfitness.domain.workouts.Location
import com.gte619n.healthfitness.domain.workouts.UpdateLocationRequest
import java.time.Instant

/**
 * Wire mirror of a gym location. Plain data class decoded by Moshi's
 * reflective [com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory]
 * (registered on the base Moshi). `hours` keys are lowercase day strings
 * handled by the global [com.gte619n.healthfitness.data.net.DayOfWeekMoshiAdapter].
 */
data class LocationDto(
    val locationId: String,
    val name: String,
    val address: String? = null,
    val coverPhotoUrl: String? = null,
    val is24Hours: Boolean = false,
    val hours: Map<DayOfWeek, HoursSlotDto>? = null,
    val amenities: List<String> = emptyList(),
    val equipmentIds: List<String> = emptyList(),
    val equipmentSpecs: Map<String, Map<String, Any?>> = emptyMap(),
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: String,
    val updatedAt: String,
)

data class HoursSlotDto(
    val open: String,
    val close: String,
)

data class CreateLocationDto(
    val name: String,
    val address: String?,
    val is24Hours: Boolean,
    val hours: Map<DayOfWeek, HoursSlotDto>?,
    val amenities: List<String>,
    val equipmentIds: List<String>,
)

data class UpdateLocationDto(
    val name: String? = null,
    val address: String? = null,
    val is24Hours: Boolean? = null,
    val hours: Map<DayOfWeek, HoursSlotDto>? = null,
    val amenities: List<String>? = null,
    val equipmentIds: List<String>? = null,
)

/** Body for PATCH /api/me/gyms/{id}/equipment/{equipmentId}. */
data class SpecsPatchDto(
    val specs: Map<String, Any?>,
)

// ---- Mappers ----

fun HoursSlotDto.toDomain(): HoursSlot = HoursSlot(open = open, close = close)

fun HoursSlot.toDto(): HoursSlotDto = HoursSlotDto(open = open, close = close)

fun LocationDto.toDomain(): Location = Location(
    locationId = locationId,
    name = name,
    address = address,
    coverPhotoUrl = coverPhotoUrl,
    is24Hours = is24Hours,
    hours = hours?.mapValues { it.value.toDomain() },
    amenities = amenities.mapNotNull { Amenity.fromId(it) },
    equipmentIds = equipmentIds,
    equipmentSpecs = equipmentSpecs,
    isDefault = isDefault,
    isActive = isActive,
    createdAt = Instant.parse(createdAt),
    updatedAt = Instant.parse(updatedAt),
)

fun CreateLocationRequest.toDto(): CreateLocationDto = CreateLocationDto(
    name = name,
    address = address,
    is24Hours = is24Hours,
    hours = hours?.takeUnless { is24Hours }?.mapValues { it.value.toDto() },
    amenities = amenities,
    equipmentIds = equipmentIds,
)

fun UpdateLocationRequest.toDto(): UpdateLocationDto = UpdateLocationDto(
    name = name,
    address = address,
    is24Hours = is24Hours,
    hours = hours?.mapValues { it.value.toDto() },
    amenities = amenities,
    equipmentIds = equipmentIds,
)
