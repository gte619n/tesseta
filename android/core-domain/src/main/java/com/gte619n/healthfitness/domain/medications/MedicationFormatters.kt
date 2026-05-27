package com.gte619n.healthfitness.domain.medications

import java.time.LocalDate
import java.time.DayOfWeek as JavaDayOfWeek
import java.time.temporal.ChronoUnit

/**
 * Pure, unit-testable formatters and helpers used by both the medications
 * feature module and the dashboard's TodaysDoses card. Lives in
 * `core-domain` so identical strings appear everywhere ("200 mg" not
 * "200.0 mg").
 */
object DoseFormatter {
    /** "200 mg", "0.5 mg", "12.5 mg" — integers stay integers, fractions show one decimal. */
    fun format(dose: Double, unit: String): String {
        val isWhole = dose % 1.0 == 0.0
        val n = if (isWhole) dose.toInt().toString() else "%.1f".format(dose)
        return "$n $unit"
    }

    fun formatDoseOnly(dose: Double): String {
        val isWhole = dose % 1.0 == 0.0
        return if (isWhole) dose.toInt().toString() else "%.1f".format(dose)
    }
}

object FrequencyFormatter {
    fun format(f: FrequencyConfig): String = when (f.type) {
        FrequencyType.DAILY -> if ((f.timesPerPeriod ?: 1) == 1) "Once daily" else "${f.timesPerPeriod}x daily"
        FrequencyType.WEEKLY -> "${f.timesPerPeriod ?: 1}x weekly"
        FrequencyType.MONTHLY -> "Monthly"
        FrequencyType.PRN -> "As needed"
        FrequencyType.CYCLE -> f.cycle?.let { "${it.onWeeks}w on / ${it.offWeeks}w off" } ?: "Cycle"
    }
}

object TimeWindowLabels {
    fun label(w: TimeWindow): String = when (w) {
        TimeWindow.MORNING -> "Morning"
        TimeWindow.AFTERNOON -> "Afternoon"
        TimeWindow.EVENING -> "Evening"
        TimeWindow.BEDTIME -> "Bedtime"
    }

    /** Short caps label used by the dashboard Today card. */
    fun shortLabel(w: TimeWindow): String = when (w) {
        TimeWindow.MORNING -> "AM"
        TimeWindow.AFTERNOON -> "NOON"
        TimeWindow.EVENING -> "PM"
        TimeWindow.BEDTIME -> "BED"
    }
}

object DiscontinueReasonLabels {
    fun label(r: DiscontinueReason): String = when (r) {
        DiscontinueReason.COMPLETED -> "Completed"
        DiscontinueReason.SIDE_EFFECTS -> "Side effects"
        DiscontinueReason.SWITCHED -> "Switched"
        DiscontinueReason.COST -> "Cost"
        DiscontinueReason.OTHER -> "Other"
    }
}

/**
 * Pure computation: how many doses of [config] + [timeSlots] are due
 * today? Used by tests as a parity check against the backend's
 * `TodaysDosesController.isScheduledForToday` logic, and by the UI to
 * show a graceful "no doses today" fallback.
 *
 * Logic mirrors the backend:
 *  - DAILY → always scheduled
 *  - WEEKLY with specificDays → only on those days
 *  - WEEKLY without specificDays → every day (matches backend)
 *  - MONTHLY → always (backend approximation; no specific-day support)
 *  - PRN → never
 *  - CYCLE → only inside the "on" weeks of the rolling cycle
 *
 * Returns the count of [TimeSlot]s if scheduled today, otherwise 0.
 */
object FrequencyConfigSchedule {
    fun dosesToday(
        config: FrequencyConfig,
        timeSlots: List<TimeSlot>,
        today: LocalDate,
    ): Int {
        val scheduled = isScheduledToday(config, today)
        if (!scheduled) return 0
        return if (timeSlots.isEmpty()) 1 else timeSlots.size
    }

    fun isScheduledToday(config: FrequencyConfig, today: LocalDate): Boolean {
        return when (config.type) {
            FrequencyType.DAILY -> true
            FrequencyType.WEEKLY -> {
                val days = config.specificDays
                if (days.isNullOrEmpty()) {
                    true
                } else {
                    days.any { toJavaDayOfWeek(it) == today.dayOfWeek }
                }
            }
            FrequencyType.MONTHLY -> true
            FrequencyType.PRN -> false
            FrequencyType.CYCLE -> {
                val cycle = config.cycle ?: return true
                val totalWeeks = cycle.onWeeks + cycle.offWeeks
                if (totalWeeks <= 0) return false
                val daysSinceStart = ChronoUnit.DAYS.between(cycle.startDate, today)
                if (daysSinceStart < 0) return false
                val cycleLengthDays = totalWeeks * 7L
                val dayInCycle = daysSinceStart % cycleLengthDays
                dayInCycle < cycle.onWeeks * 7L
            }
        }
    }

    private fun toJavaDayOfWeek(day: DayOfWeek): JavaDayOfWeek = when (day) {
        DayOfWeek.MON -> JavaDayOfWeek.MONDAY
        DayOfWeek.TUE -> JavaDayOfWeek.TUESDAY
        DayOfWeek.WED -> JavaDayOfWeek.WEDNESDAY
        DayOfWeek.THU -> JavaDayOfWeek.THURSDAY
        DayOfWeek.FRI -> JavaDayOfWeek.FRIDAY
        DayOfWeek.SAT -> JavaDayOfWeek.SATURDAY
        DayOfWeek.SUN -> JavaDayOfWeek.SUNDAY
    }
}
