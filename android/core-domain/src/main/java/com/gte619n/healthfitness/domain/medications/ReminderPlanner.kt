package com.gte619n.healthfitness.domain.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure "is this medication due on this date?" scheduling rule — the frequency /
 * day-of-week / cycle / start-end logic shared by the reminder feature.
 *
 * IMPL-21: the old per-window `plan()` that produced a grouped list of future
 * reminders was removed with the multi-notification engine (decision D-6/D12). The
 * single rolling reminder computes what's outstanding via [OutstandingDoses], which
 * reuses [isDueOn] here so the due-date rule lives in exactly one place.
 */
object ReminderPlanner {

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
