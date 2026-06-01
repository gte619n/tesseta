package com.gte619n.healthfitness.feature.workouts.ui

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.Amenity
import com.gte619n.healthfitness.domain.workouts.HoursSlot

/**
 * Shared editable form state for both New and Edit gym screens. `hours` keeps
 * a null entry per closed day so the matrix can round-trip "Closed".
 */
data class LocationFormState(
    val name: String = "",
    val address: String = "",
    val is24Hours: Boolean = false,
    val hours: Map<DayOfWeek, HoursSlot?> = DayOfWeek.entries.associateWith { null },
    val amenities: Set<Amenity> = emptySet(),
    val submitting: Boolean = false,
    val error: String? = null,
)

/** Validation shared by New/Edit. Returns an error message or null if valid. */
fun validateLocationForm(state: LocationFormState): String? = when {
    state.name.isBlank() -> "Name is required"
    !state.is24Hours && state.hours.values.all { it == null } -> "Set at least one day's hours"
    else -> null
}

/** Maps the editable hours (nullable per day) to the wire form, dropping closed days. */
fun LocationFormState.hoursForWire(): Map<DayOfWeek, HoursSlot>? {
    if (is24Hours) return null
    val open = hours.filterValues { it != null }.mapValues { it.value!! }
    return open.ifEmpty { null }
}
