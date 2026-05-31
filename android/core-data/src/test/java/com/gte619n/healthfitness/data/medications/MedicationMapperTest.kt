package com.gte619n.healthfitness.data.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.DrugCategory
import com.gte619n.healthfitness.domain.medications.DrugForm
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class MedicationMapperTest {

    private fun drugDto() = DrugDto(
        drugId = "d1",
        name = "Testosterone Cypionate",
        aliases = listOf("Test Cyp"),
        category = "PRESCRIPTION",
        form = "INJECTABLE_VIAL",
        defaultUnit = "mg",
        commonDoses = listOf("100", "200"),
        imageUrl = "https://img/x.png",
        imageFallback = "fallback.png",
        suggestedMarkers = listOf("TESTOSTERONE_TOTAL"),
        description = "desc",
    )

    @Test
    fun `drug enums map and lists default to empty`() {
        val drug = MedicationMapper.toDomain(drugDto().copy(aliases = null, commonDoses = null))
        assertEquals(DrugCategory.PRESCRIPTION, drug.category)
        assertEquals(DrugForm.INJECTABLE_VIAL, drug.form)
        assertTrue(drug.aliases.isEmpty())
        assertTrue(drug.commonDoses.isEmpty())
    }

    @Test
    fun `unknown enum value falls back without throwing`() {
        val drug = MedicationMapper.toDomain(drugDto().copy(category = "MYSTERY", form = "WIDGET"))
        assertEquals(DrugCategory.OTC, drug.category)
        assertEquals(DrugForm.TABLET, drug.form)
    }

    @Test
    fun `medication maps with null drug and customName`() {
        val dto = MedicationDto(
            medicationId = "m1",
            drugId = null,
            drug = null,
            customName = "My Custom",
            status = "ACTIVE",
            dose = 5.0,
            unit = "mg",
            frequency = FrequencyConfigDto(type = "DAILY", timesPerPeriod = 1),
            timeSlots = null,
            startDate = LocalDate.of(2026, 1, 1),
            endDate = null,
            discontinueReason = null,
            correlatedMarkers = null,
            adherence = null,
        )
        val med = MedicationMapper.toDomain(dto)
        assertNull(med.drug)
        assertEquals("My Custom", med.customName)
        assertEquals("My Custom", med.displayName)
        assertEquals(MedicationStatus.ACTIVE, med.status)
        assertEquals(FrequencyType.DAILY, med.frequency.type)
        assertTrue(med.timeSlots.isEmpty())
        assertNull(med.endDate)
    }

    @Test
    fun `discontinued medication maps reason and endDate`() {
        val dto = MedicationDto(
            medicationId = "m2",
            drug = drugDto(),
            status = "DISCONTINUED",
            dose = 200.0,
            unit = "mg",
            frequency = FrequencyConfigDto(type = "WEEKLY", timesPerPeriod = 1),
            startDate = LocalDate.of(2026, 1, 1),
            endDate = LocalDate.of(2026, 3, 1),
            discontinueReason = "SWITCHED",
            discontinueNotes = "moved to enanthate",
        )
        val med = MedicationMapper.toDomain(dto)
        assertEquals(MedicationStatus.DISCONTINUED, med.status)
        assertEquals(DiscontinueReason.SWITCHED, med.discontinueReason)
        assertEquals(LocalDate.of(2026, 3, 1), med.endDate)
    }

    @Test
    fun `dosagePeriods round-trip through JSON preserving active period`() {
        // [PR#8] verify DosagePeriod round-trips via the real Moshi config.
        val json = """
            {
              "medicationId":"m3",
              "drugId":null,"drug":null,"customName":"X","status":"ACTIVE",
              "dose":250.0,"unit":"mg",
              "frequency":{"type":"WEEKLY","timesPerPeriod":1,"specificDays":["mon","wed"]},
              "timeSlots":[{"window":"MORNING","dose":250.0}],
              "startDate":"2026-01-01","endDate":null,
              "correlatedMarkers":[],
              "dosagePeriods":[
                {"dose":200.0,"unit":"mg","startDate":"2026-01-01","endDate":"2026-02-01"},
                {"dose":250.0,"unit":"mg","startDate":"2026-02-01","endDate":null}
              ]
            }
        """.trimIndent()
        val adapter = MedsTestMoshi.instance.adapter(MedicationDto::class.java)
        val dto = adapter.fromJson(json)!!
        val med = MedicationMapper.toDomain(dto)

        assertEquals(2, med.dosagePeriods.size)
        assertEquals(200.0, med.dosagePeriods[0].dose, 0.0)
        assertEquals(LocalDate.of(2026, 2, 1), med.dosagePeriods[0].endDate)
        assertTrue(med.dosagePeriods[1].isActive)
        // lowercase DayOfWeek decoded
        assertEquals(listOf(DayOfWeek.MON, DayOfWeek.WED), med.frequency.specificDays)
        assertEquals(TimeWindow.MORNING, med.timeSlots.first().window)
    }

    @Test
    fun `specificDays serialize lowercase on the wire`() {
        // [PR#8] DayOfWeek lowercase adapter.
        val dto = FrequencyConfigDto(
            type = "WEEKLY",
            timesPerPeriod = 3,
            specificDays = listOf(DayOfWeek.MON, DayOfWeek.FRI),
        )
        val json = MedsTestMoshi.instance.adapter(FrequencyConfigDto::class.java).toJson(dto)
        assertTrue(json.contains("\"mon\""))
        assertTrue(json.contains("\"fri\""))
    }
}
