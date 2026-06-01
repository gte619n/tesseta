package com.gte619n.healthfitness.data.workouts

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.Amenity
import com.gte619n.healthfitness.domain.workouts.CreateLocationRequest
import com.gte619n.healthfitness.domain.workouts.HoursSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocationDtoMapperTest {

    @Test
    fun `amenity ids map to enums and unknown ids are dropped`() {
        val dto = LocationDto(
            locationId = "g1",
            name = "Gym",
            amenities = listOf("lockers", "showers", "bogus"),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
        val domain = dto.toDomain()
        assertEquals(listOf(Amenity.LOCKERS, Amenity.SHOWERS), domain.amenities)
    }

    @Test
    fun `hours map with omitted days round-trips closed days as absent`() {
        val dto = LocationDto(
            locationId = "g1",
            name = "Gym",
            hours = mapOf(
                DayOfWeek.MON to HoursSlotDto("09:00", "17:00"),
                DayOfWeek.WED to HoursSlotDto("06:00", "22:00"),
            ),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
        val domain = dto.toDomain()
        assertEquals(setOf(DayOfWeek.MON, DayOfWeek.WED), domain.hours?.keys)
        assertEquals(HoursSlot("09:00", "17:00"), domain.hours?.get(DayOfWeek.MON))
        assertNull(domain.hours?.get(DayOfWeek.TUE))
    }

    @Test
    fun `equipmentSpecs map passes through untyped`() {
        val dto = LocationDto(
            locationId = "g1",
            name = "Gym",
            equipmentSpecs = mapOf("eq-1" to mapOf("maxWeight" to 150.0)),
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = "2026-01-01T00:00:00Z",
        )
        val domain = dto.toDomain()
        assertEquals(150.0, domain.equipmentSpecs["eq-1"]?.get("maxWeight"))
    }

    @Test
    fun `create request omits hours when 24 hours`() {
        val req = CreateLocationRequest(
            name = "Gym",
            address = null,
            is24Hours = true,
            hours = mapOf(DayOfWeek.MON to HoursSlot("09:00", "17:00")),
            amenities = listOf("wifi"),
        )
        val dto = req.toDto()
        assertNull(dto.hours)
        assertEquals(listOf("wifi"), dto.amenities)
    }
}
