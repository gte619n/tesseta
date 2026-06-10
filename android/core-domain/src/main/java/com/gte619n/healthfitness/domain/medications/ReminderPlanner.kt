package com.gte619n.healthfitness.domain.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * Pure scheduling logic for medication reminders: given the user's active
 * medications, their reminder settings and what's already been taken, compute
 * the upcoming reminders — grouped so every dose resolving to the same local
 * time shares one notification. No framework dependencies; the device-side
 * engine turns the first [PlannedReminder] into an exact alarm.
 */
object ReminderPlanner {

    /**
     * All reminders strictly after [from] within the next [days] days,
     * soonest first.
     *
     * @param takenToday `(medicationId, window)` pairs already logged for
     *   [from]'s date — those doses are dropped from today's reminders.
     */
    fun plan(
        medications: List<Medication>,
        settings: ReminderSettings,
        takenToday: Set<Pair<String, TimeWindow>>,
        from: LocalDateTime,
        days: Int = 2,
    ): List<PlannedReminder> {
        if (!settings.enabled) return emptyList()
        val reminders = mutableMapOf<LocalDateTime, MutableList<ReminderDose>>()
        for (offset in 0 until days) {
            val date = from.toLocalDate().plusDays(offset.toLong())
            for (med in medications) {
                if (!settings.enabledFor(med.medicationId)) continue
                if (!isDueOn(med, date)) continue
                for (slot in slotsOf(med)) {
                    if (offset == 0 && (med.medicationId to slot.window) in takenToday) continue
                    val at = date.atTime(settings.timeFor(med.medicationId, slot.window))
                    if (!at.isAfter(from)) continue
                    reminders.getOrPut(at) { mutableListOf() }.add(
                        ReminderDose(
                            medicationId = med.medicationId,
                            name = med.displayName,
                            window = slot.window,
                            dose = slot.dose,
                            unit = med.unit,
                        ),
                    )
                }
            }
        }
        return reminders.entries
            .sortedBy { it.key }
            .map { (at, doses) -> PlannedReminder(at, doses.sortedBy { it.name }) }
    }

    /**
     * Whether [med] has scheduled doses on [date]. PRN ("as needed") never
     * schedules; the others follow their frequency config. Discontinued and
     * not-yet-started medications are excluded.
     */
    fun isDueOn(med: Medication, date: LocalDate): Boolean {
        if (med.status != MedicationStatus.ACTIVE) return false
        if (date.isBefore(med.startDate)) return false
        med.endDate?.let { if (date.isAfter(it)) return false }
        return when (med.frequency.type) {
            FrequencyType.DAILY -> true
            FrequencyType.PRN -> false
            FrequencyType.WEEKLY -> {
                val days = med.frequency.specificDays
                days.isNullOrEmpty() || days.contains(date.dayOfWeek.toDomain())
            }
            FrequencyType.MONTHLY ->
                // Same day-of-month as the start date, clamped for short months.
                date.dayOfMonth == med.startDate.dayOfMonth.coerceAtMost(date.lengthOfMonth())
            FrequencyType.CYCLE -> {
                val cycle = med.frequency.cycle ?: return true
                val weeksSinceStart = ChronoUnit.WEEKS.between(cycle.startDate, date)
                if (weeksSinceStart < 0) return false
                val period = cycle.onWeeks + cycle.offWeeks
                if (period <= 0) return true
                (weeksSinceStart % period) < cycle.onWeeks
            }
        }
    }

    /** A medication with no explicit slots defaults to one MORNING dose. */
    private fun slotsOf(med: Medication): List<TimeSlot> =
        med.timeSlots.ifEmpty { listOf(TimeSlot(TimeWindow.MORNING, med.dose)) }

    private fun java.time.DayOfWeek.toDomain(): DayOfWeek = when (this) {
        java.time.DayOfWeek.MONDAY -> DayOfWeek.MON
        java.time.DayOfWeek.TUESDAY -> DayOfWeek.TUE
        java.time.DayOfWeek.WEDNESDAY -> DayOfWeek.WED
        java.time.DayOfWeek.THURSDAY -> DayOfWeek.THU
        java.time.DayOfWeek.FRIDAY -> DayOfWeek.FRI
        java.time.DayOfWeek.SATURDAY -> DayOfWeek.SAT
        java.time.DayOfWeek.SUNDAY -> DayOfWeek.SUN
    }
}
