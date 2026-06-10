package com.gte619n.healthfitness.domain.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderPlannerTest {

    // Mon 2026-06-08; "now" is 05:00 so the 06:00 MORNING reminders are ahead.
    private val monday = LocalDate.of(2026, 6, 8)
    private val now = monday.atTime(5, 0)
    private val settings = ReminderSettings()

    @Test
    fun groupsMedsSharingAFireTime_intoOneReminder() {
        val meds = listOf(
            med("m1", "Metformin", slots = listOf(TimeSlot(TimeWindow.MORNING, 500.0))),
            med("m2", "Vitamin D", slots = listOf(TimeSlot(TimeWindow.MORNING, 5000.0))),
            med("m3", "Statin", slots = listOf(TimeSlot(TimeWindow.BEDTIME, 20.0))),
        )

        val plan = ReminderPlanner.plan(meds, settings, emptySet(), now, days = 1)

        assertEquals(2, plan.size)
        val morning = plan.first()
        assertEquals(monday.atTime(6, 0), morning.at)
        assertEquals(listOf("Metformin", "Vitamin D"), morning.doses.map { it.name })
        assertEquals(monday.atTime(21, 30), plan[1].at)
    }

    @Test
    fun perMedOverride_beatsUserWindowTime_andMuteDropsTheMed() {
        val meds = listOf(
            med("m1", "Metformin", slots = listOf(TimeSlot(TimeWindow.MORNING, 500.0))),
            med("m2", "Vitamin D", slots = listOf(TimeSlot(TimeWindow.MORNING, 5000.0))),
        )
        val custom = settings.copy(
            perMedication = mapOf(
                "m1" to MedicationReminderOverride(times = mapOf(TimeWindow.MORNING to "07:30")),
                "m2" to MedicationReminderOverride(enabled = false),
            ),
        )

        val plan = ReminderPlanner.plan(meds, custom, emptySet(), now, days = 1)

        assertEquals(1, plan.size)
        assertEquals(monday.atTime(7, 30), plan[0].at)
        assertEquals(listOf("Metformin"), plan[0].doses.map { it.name })
    }

    @Test
    fun takenDoses_dropOutOfToday_butNotTomorrow() {
        val meds = listOf(
            med("m1", "Metformin", slots = listOf(TimeSlot(TimeWindow.MORNING, 500.0))),
        )

        val plan = ReminderPlanner.plan(
            meds, settings, setOf("m1" to TimeWindow.MORNING), now, days = 2)

        assertEquals(1, plan.size)
        assertEquals(monday.plusDays(1).atTime(6, 0), plan[0].at)
    }

    @Test
    fun prn_never_schedules_andDisabledMasterSwitchPlansNothing() {
        val prn = med("m1", "Ibuprofen", frequency = FrequencyConfig(FrequencyType.PRN))
        assertTrue(ReminderPlanner.plan(listOf(prn), settings, emptySet(), now).isEmpty())

        val daily = med("m2", "Metformin")
        val off = settings.copy(enabled = false)
        assertTrue(ReminderPlanner.plan(listOf(daily), off, emptySet(), now).isEmpty())
    }

    @Test
    fun weekly_firesOnlyOnItsDays() {
        val mwf = med(
            "m1", "Alendronate",
            frequency = FrequencyConfig(
                FrequencyType.WEEKLY,
                specificDays = listOf(DayOfWeek.MON, DayOfWeek.FRI),
            ),
        )
        assertTrue(ReminderPlanner.isDueOn(mwf, monday))
        assertFalse(ReminderPlanner.isDueOn(mwf, monday.plusDays(1)))
        assertTrue(ReminderPlanner.isDueOn(mwf, monday.plusDays(4)))
    }

    @Test
    fun cycle_respectsOnOffWeeks() {
        val cycled = med(
            "m1", "Peptide",
            frequency = FrequencyConfig(
                FrequencyType.CYCLE,
                cycle = FrequencyConfig.CycleConfig(
                    onWeeks = 2, offWeeks = 1, startDate = monday.minusWeeks(2)),
            ),
        )
        // Weeks 0-1 on, week 2 off, weeks 3-4 on…
        assertFalse(ReminderPlanner.isDueOn(cycled, monday))
        assertTrue(ReminderPlanner.isDueOn(cycled, monday.plusWeeks(1)))
    }

    @Test
    fun discontinued_andNotYetStarted_areExcluded() {
        val stopped = med("m1", "Old", status = MedicationStatus.DISCONTINUED)
        assertFalse(ReminderPlanner.isDueOn(stopped, monday))

        val future = med("m2", "New", startDate = monday.plusDays(3))
        assertFalse(ReminderPlanner.isDueOn(future, monday))
        assertTrue(ReminderPlanner.isDueOn(future, monday.plusDays(3)))
    }

    @Test
    fun medWithoutSlots_defaultsToOneMorningDose() {
        val plan = ReminderPlanner.plan(
            listOf(med("m1", "Metformin", slots = emptyList())),
            settings, emptySet(), now, days = 1)
        assertEquals(1, plan.size)
        assertEquals(TimeWindow.MORNING, plan[0].doses.single().window)
    }

    // ---- fixture ----------------------------------------------------------

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
