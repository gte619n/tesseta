package com.gte619n.healthfitness.domain.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Test

/**
 * IMPL-21 Phase 1 — the authoritative proof of the rolling/decrement behavior
 * (spec §6.2). Drives the pure reducer at explicit clocks and asserts the
 * canonical 5 → 1 → 3 → 0 story plus edge cases.
 */
class OutstandingDosesTest {

    // Mon 2026-06-08.
    private val monday = LocalDate.of(2026, 6, 8)
    private val settings = ReminderSettings()

    // 5 morning doses @07:00, 2 afternoon doses @13:00 (explicit slot times).
    private val fiveMorning = (1..5).map {
        med("m$it", "Morning$it", slots = listOf(slot(TimeWindow.MORNING, "07:00")))
    }
    private val twoAfternoon = (1..2).map {
        med("a$it", "Afternoon$it", slots = listOf(slot(TimeWindow.AFTERNOON, "13:00")))
    }
    private val allMeds = fiveMorning + twoAfternoon

    private fun keys(list: List<DueDose>) = list.map { it.key }

    // ---- the canonical story -------------------------------------------------

    @Test
    fun at0700_allFiveMorningDosesAreOutstanding() {
        val out = OutstandingDoses.outstanding(allMeds, settings, emptySet(), monday.atTime(7, 0))
        assertEquals(5, out.size)
        assertTrue(out.all { it.window == TimeWindow.MORNING })
    }

    @Test
    fun afterTakingFour_onlyOneRemains() {
        val taken = (1..4).map { "m$it" to TimeWindow.MORNING }.toSet()
        val out = OutstandingDoses.outstanding(allMeds, settings, taken, monday.atTime(7, 5))
        assertEquals(1, out.size)
        assertEquals("m5:MORNING", out.single().key)
    }

    @Test
    fun at1300_overdueMorningPlusTwoAfternoon_mostOverdueFirst() {
        // 4 of 5 morning taken; morning #5 still outstanding when the afternoon lands.
        val taken = (1..4).map { "m$it" to TimeWindow.MORNING }.toSet()
        val out = OutstandingDoses.outstanding(allMeds, settings, taken, monday.atTime(13, 0))
        assertEquals(3, out.size)
        // Most-overdue first: the 07:00 morning dose precedes the 13:00 afternoon ones.
        assertEquals("m5:MORNING", keys(out).first())
        assertEquals(setOf("a1:AFTERNOON", "a2:AFTERNOON"), keys(out).drop(1).toSet())
        assertTrue(out[0].time.isBefore(out[1].time))
    }

    @Test
    fun whenAllTaken_nothingOutstanding() {
        val taken = (fiveMorning.map { it.medicationId to TimeWindow.MORNING } +
            twoAfternoon.map { it.medicationId to TimeWindow.AFTERNOON }).toSet()
        assertTrue(OutstandingDoses.outstanding(allMeds, settings, taken, monday.atTime(20, 0)).isEmpty())
    }

    // ---- hiding / ordering ---------------------------------------------------

    @Test
    fun laterTodayDosesAreHiddenUntilTheirTime() {
        // Before 07:00 nothing is due; the afternoon batch never shows this early.
        assertTrue(OutstandingDoses.outstanding(allMeds, settings, emptySet(), monday.atTime(6, 59)).isEmpty())
        // Just after 07:00 the afternoon doses are still hidden.
        val out = OutstandingDoses.outstanding(allMeds, settings, emptySet(), monday.atTime(7, 1))
        assertEquals(5, out.size)
        assertTrue(out.none { it.window == TimeWindow.AFTERNOON })
    }

    // ---- exclusions ----------------------------------------------------------

    @Test
    fun prnNeverOutstanding() {
        val prn = med("p", "Ibuprofen", frequency = FrequencyConfig(FrequencyType.PRN),
            slots = listOf(slot(TimeWindow.MORNING, "07:00")))
        assertTrue(OutstandingDoses.outstanding(listOf(prn), settings, emptySet(), monday.atTime(9, 0)).isEmpty())
    }

    @Test
    fun mutedMedicationIsExcluded() {
        val muted = settings.copy(perMedication = mapOf("m1" to MedicationReminderOverride(enabled = false)))
        val out = OutstandingDoses.outstanding(fiveMorning, muted, emptySet(), monday.atTime(8, 0))
        assertEquals(4, out.size)
        assertTrue(out.none { it.medicationId == "m1" })
    }

    @Test
    fun weeklyNotToday_isNotScheduled() {
        val tuesdayOnly = med("w", "Alendronate",
            frequency = FrequencyConfig(FrequencyType.WEEKLY, specificDays = listOf(DayOfWeek.TUE)),
            slots = listOf(slot(TimeWindow.MORNING, "07:00")))
        assertTrue(OutstandingDoses.outstanding(listOf(tuesdayOnly), settings, emptySet(), monday.atTime(9, 0)).isEmpty())
    }

    // ---- precedence (spec D2) -----------------------------------------------

    @Test
    fun explicitSlotTime_overridesWindowDefault() {
        // No explicit time ⇒ MORNING default 06:00; with explicit 09:30 it is hidden at 07:00.
        val defaulted = med("d", "Default", slots = listOf(TimeSlot(TimeWindow.MORNING, 1.0)))
        assertEquals(1, OutstandingDoses.outstanding(listOf(defaulted), settings, emptySet(), monday.atTime(7, 0)).size)

        val explicit = med("e", "Explicit", slots = listOf(slot(TimeWindow.MORNING, "09:30")))
        assertTrue(OutstandingDoses.outstanding(listOf(explicit), settings, emptySet(), monday.atTime(7, 0)).isEmpty())
        assertEquals(1, OutstandingDoses.outstanding(listOf(explicit), settings, emptySet(), monday.atTime(9, 30)).size)
    }

    // ---- nextDueTime (alarm arming) -----------------------------------------

    @Test
    fun nextDueTime_picksTheNextBatchBoundary() {
        // Before anything: the 07:00 morning batch.
        assertEquals(monday.atTime(7, 0), OutstandingDoses.nextDueTime(allMeds, settings, monday.atTime(6, 0)))
        // After morning due, before afternoon: the 13:00 batch.
        assertEquals(monday.atTime(13, 0), OutstandingDoses.nextDueTime(allMeds, settings, monday.atTime(7, 30)))
        // After the last dose today: rolls to tomorrow's first (07:00).
        assertEquals(monday.plusDays(1).atTime(7, 0), OutstandingDoses.nextDueTime(allMeds, settings, monday.atTime(14, 0)))
    }

    @Test
    fun nextDueTime_nullWhenNothingScheduled() {
        val prn = med("p", "Ibuprofen", frequency = FrequencyConfig(FrequencyType.PRN))
        assertNull(OutstandingDoses.nextDueTime(listOf(prn), settings, monday.atTime(6, 0)))
    }

    @Test
    fun scheduledFor_isMissedRolloverSource() {
        // All 7 doses are "scheduled" for the day regardless of time/taken.
        assertEquals(7, OutstandingDoses.scheduledFor(allMeds, settings, monday).size)
    }

    // ---- fixture -------------------------------------------------------------

    private fun slot(window: TimeWindow, time: String) =
        TimeSlot(window, 1.0, LocalTime.parse(time))

    private fun med(
        id: String,
        name: String,
        slots: List<TimeSlot> = listOf(TimeSlot(TimeWindow.MORNING, 1.0)),
        frequency: FrequencyConfig = FrequencyConfig(FrequencyType.DAILY),
        status: MedicationStatus = MedicationStatus.ACTIVE,
        startDate: LocalDate = LocalDate.of(2026, 1, 1),
    ): Medication = Medication(
        medicationId = id,
        drugId = null,
        drug = null,
        customName = name,
        status = status,
        dose = slots.firstOrNull()?.dose ?: 1.0,
        unit = "mg",
        frequency = frequency,
        timeSlots = slots,
        protocolId = null,
        notes = null,
        prescribedBy = null,
        startDate = startDate,
        endDate = null,
        discontinueReason = null,
        discontinueNotes = null,
        correlatedMarkers = emptyList(),
        adherence = null,
    )
}
