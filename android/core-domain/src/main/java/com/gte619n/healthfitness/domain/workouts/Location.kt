package com.gte619n.healthfitness.domain.workouts

import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.time.Instant

/**
 * Opening hours for a single day. Times are 24-hour "HH:mm" strings on
 * 15-minute increments (e.g. "06:00", "21:45"). A null day entry means the
 * gym is closed that day.
 */
data class HoursSlot(
    val open: String,
    val close: String,
)

/**
 * Hardcoded amenity catalog. IDs mirror web's `web/lib/types/gym.ts`
 * `AMENITIES` exactly and are stored lowercase on the wire.
 */
enum class Amenity(val id: String, val label: String) {
    TWENTY_FOUR_HR("24hr", "24-Hour Access"),
    LOCKERS("lockers", "Lockers"),
    SHOWERS("showers", "Showers"),
    PARKING("parking", "Parking"),
    WIFI("wifi", "WiFi"),
    TOWELS("towels", "Towels"),
    SAUNA("sauna", "Sauna"),
    POOL("pool", "Pool"),
    CHILDCARE("childcare", "Childcare"),
    TRAINING("training", "Personal Training");

    companion object {
        fun fromId(id: String): Amenity? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A user gym location. `hours` is keyed by [DayOfWeek] (lowercase on the
 * wire); closed days are simply absent. `equipmentSpecs` is the free-form
 * per-location override map keyed by equipmentId — intentionally untyped to
 * mirror web's storage (partial overrides are valid).
 */
data class Location(
    val locationId: String,
    val name: String,
    val address: String?,
    val coverPhotoUrl: String?,
    val is24Hours: Boolean,
    val hours: Map<DayOfWeek, HoursSlot>?,
    val amenities: List<Amenity>,
    val equipmentIds: List<String>,
    val equipmentSpecs: Map<String, Map<String, Any?>>,
    val isDefault: Boolean,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
)
