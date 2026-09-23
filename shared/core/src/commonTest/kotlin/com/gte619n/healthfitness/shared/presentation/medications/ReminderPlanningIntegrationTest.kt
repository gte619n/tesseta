package com.gte619n.healthfitness.shared.presentation.medications

import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import com.gte619n.healthfitness.shared.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.shared.domain.medications.FrequencyType
import com.gte619n.healthfitness.shared.domain.medications.MedicationReminderOverride
import com.gte619n.healthfitness.shared.domain.medications.MedicationStatus
import com.gte619n.healthfitness.shared.domain.medications.OutstandingDoses
import com.gte619n.healthfitness.shared.domain.medications.ReminderPlanner
import com.gte619n.healthfitness.shared.domain.medications.ReminderSettings
import com.gte619n.healthfitness.shared.domain.medications.TimeSlot
import com.gte619n.healthfitness.shared.domain.medications.TimeWindow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave B — the reminder-PLANNING integration the D9
 * `LocalReminderScheduler` (iOS) and Android's `ReminderEngine` both consume. It
 * pins the shared decision the platform delivery layer must NOT re-derive: WHICH
 * doses are due on a date ([ReminderPlanner.isDueOn] / [OutstandingDoses.scheduledFor]),
 * at WHAT resolved time (drug-setup → per-med override → global window default),
 * and the CARRYOVER of overdue-and-untaken doses at "now" ([OutstandingDoses.outstanding]).
 *
 * These are the exact rules the Android reminder-bug memory encodes (single
 * rolling reminder, overdue-first, taken doses drop out, muted meds excluded) —
 * shared once so the iOS scheduler and Android engine can never diverge.
 */
class ReminderPlanningIntegrationTest {

    private val settings = ReminderSettings() // enabled, default window times

    // ---- which doses / when (scheduledFor + isDueOn + time resolution) ---------

    @Test
    fun dailyMedIsDueEveryDayAtItsWindowDefaultTime() {
        val med = sampleMedication(
            timeSlots = listOf(TimeSlot(TimeWindow.MORNING, 1.0)),
        )
        val date = LocalDate(2026, 9, 23)
        assertTrue(ReminderPlanner.isDueOn(med, date))

        val scheduled = OutstandingDoses.scheduledFor(listOf(med), settings, date)
        assertEquals(1, scheduled.size)
        // MORNING default window time is 06:00.
        assertEquals(LocalTime(6, 0), scheduled.first().time)
    }

    @Test
    fun weeklyMedOnlyDueOnItsSpecificDays() {
        // 2026-09-23 is a Wednesday.
        val med = sampleMedication(
            frequency = FrequencyConfig(
                type = FrequencyType.WEEKLY,
                specificDays = listOf(DayOfWeek.MON, DayOfWeek.THU),
            ),
        )
        assertFalse(ReminderPlanner.isDueOn(med, LocalDate(2026, 9, 23))) // Wed
        assertTrue(ReminderPlanner.isDueOn(med, LocalDate(2026, 9, 24)))  // Thu
    }

    @Test
    fun prnMedNeverSchedules() {
        val med = sampleMedication(frequency = FrequencyConfig(type = FrequencyType.PRN))
        assertFalse(ReminderPlanner.isDueOn(med, LocalDate(2026, 9, 23)))
        assertTrue(OutstandingDoses.scheduledFor(listOf(med), settings, LocalDate(2026, 9, 23)).isEmpty())
    }

    @Test
    fun perMedOverrideTimeWinsOverWindowDefault() {
        val med = sampleMedication(id = "m1", timeSlots = listOf(TimeSlot(TimeWindow.MORNING, 1.0)))
        val custom = ReminderSettings(
            perMedication = mapOf("m1" to MedicationReminderOverride(times = mapOf(TimeWindow.MORNING to "07:15"))),
        )
        val scheduled = OutstandingDoses.scheduledFor(listOf(med), custom, LocalDate(2026, 9, 23))
        assertEquals(LocalTime(7, 15), scheduled.first().time)
    }

    @Test
    fun drugSetupExplicitSlotTimeHasHighestPrecedence() {
        // slot.time set → beats even a per-med settings override (spec D2 precedence).
        val med = sampleMedication(id = "m1", timeSlots = listOf(TimeSlot(TimeWindow.MORNING, 1.0, time = LocalTime(5, 30))))
        val custom = ReminderSettings(
            perMedication = mapOf("m1" to MedicationReminderOverride(times = mapOf(TimeWindow.MORNING to "07:15"))),
        )
        val scheduled = OutstandingDoses.scheduledFor(listOf(med), custom, LocalDate(2026, 9, 23))
        assertEquals(LocalTime(5, 30), scheduled.first().time)
    }

    @Test
    fun mutedMedIsExcludedFromSchedule() {
        val med = sampleMedication(id = "m1")
        val muted = ReminderSettings(
            perMedication = mapOf("m1" to MedicationReminderOverride(enabled = false)),
        )
        assertTrue(OutstandingDoses.scheduledFor(listOf(med), muted, LocalDate(2026, 9, 23)).isEmpty())
    }

    // ---- carryover / outstanding-at-now (overdue-first, taken drops out) --------

    @Test
    fun outstandingIncludesOverdueDosesMostOverdueFirstAndHidesLaterToday() {
        val morning = sampleMedication(id = "am", timeSlots = listOf(TimeSlot(TimeWindow.MORNING, 1.0)))   // 06:00
        val evening = sampleMedication(id = "pm", timeSlots = listOf(TimeSlot(TimeWindow.EVENING, 1.0)))   // 18:00
        val meds = listOf(evening, morning)
        // now = 12:00 → morning is overdue, evening is later-today (hidden).
        val now = LocalDateTime(2026, 9, 23, 12, 0)
        val out = OutstandingDoses.outstanding(meds, settings, takenToday = emptySet(), now = now)
        assertEquals(listOf("am"), out.map { it.medicationId })
    }

    @Test
    fun takenDoseDropsOutOfOutstanding_carryoverStopsWhenLogged() {
        val med = sampleMedication(id = "m1", timeSlots = listOf(TimeSlot(TimeWindow.MORNING, 1.0)))
        val now = LocalDateTime(2026, 9, 23, 12, 0)
        val outstandingBefore = OutstandingDoses.outstanding(listOf(med), settings, emptySet(), now)
        assertEquals(1, outstandingBefore.size)

        val taken = setOf("m1" to TimeWindow.MORNING)
        val outstandingAfter = OutstandingDoses.outstanding(listOf(med), settings, taken, now)
        assertTrue(outstandingAfter.isEmpty())
    }

    @Test
    fun disabledSettingsSuppressesAllReminders() {
        val med = sampleMedication(id = "m1")
        val off = ReminderSettings(enabled = false)
        val now = LocalDateTime(2026, 9, 23, 12, 0)
        assertTrue(OutstandingDoses.outstanding(listOf(med), off, emptySet(), now).isEmpty())
        assertNull(OutstandingDoses.nextDueTime(listOf(med), off, now))
    }

    @Test
    fun nextDueTimeArmsTheNextCrossingIntoDue() {
        // At 12:00, the 18:00 evening dose is the next crossing today.
        val med = sampleMedication(id = "m1", timeSlots = listOf(TimeSlot(TimeWindow.EVENING, 1.0)))
        val now = LocalDateTime(2026, 9, 23, 12, 0)
        val next = OutstandingDoses.nextDueTime(listOf(med), settings, now)
        assertEquals(LocalDateTime(2026, 9, 23, 18, 0), next)
    }

    @Test
    fun discontinuedMedIsNeverDue() {
        val med = sampleMedication(status = MedicationStatus.DISCONTINUED)
        assertFalse(ReminderPlanner.isDueOn(med, LocalDate(2026, 9, 23)))
    }
}
