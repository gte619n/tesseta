package com.gte619n.healthfitness.domain.medications

import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure, unit-testable formatting + scheduling helpers shared by the
 * medications card, detail screen and Today's Doses card so the phone never
 * drifts from web.
 */

object DoseFormatter {
    /** Format a dose as integer when whole, else one decimal. e.g. "200 mg", "0.5 mg". */
    fun format(dose: Double, unit: String): String {
        val isWhole = dose % 1.0 == 0.0
        val n = if (isWhole) dose.toInt().toString() else "%.1f".format(dose)
        return "$n $unit"
    }
}

object FrequencyFormatter {
    fun format(f: FrequencyConfig): String = when (f.type) {
        FrequencyType.DAILY ->
            if ((f.timesPerPeriod ?: 1) == 1) "Once daily" else "${f.timesPerPeriod}x daily"
        FrequencyType.WEEKLY -> "${f.timesPerPeriod ?: 1}x weekly"
        FrequencyType.MONTHLY -> "Monthly"
        FrequencyType.PRN -> "As needed"
        FrequencyType.CYCLE ->
            f.cycle?.let { "${it.onWeeks}w on / ${it.offWeeks}w off" } ?: "Cycle"
    }
}

object TimeWindowLabels {
    fun label(w: TimeWindow): String = when (w) {
        TimeWindow.MORNING -> "Morning"
        TimeWindow.AFTERNOON -> "Afternoon"
        TimeWindow.EVENING -> "Evening"
        TimeWindow.BEDTIME -> "Bedtime"
    }
}

object DiscontinueReasonLabels {
    fun label(r: DiscontinueReason): String = when (r) {
        DiscontinueReason.COMPLETED -> "Completed course"
        DiscontinueReason.SIDE_EFFECTS -> "Side effects"
        DiscontinueReason.SWITCHED -> "Switched medication"
        DiscontinueReason.COST -> "Cost"
        DiscontinueReason.OTHER -> "Other"
    }
}

/**
 * Pure scheduling math used to derive the expected count of doses scheduled
 * for a given day from a [FrequencyConfig] + time slots. Mirrors what the
 * backend `/today` endpoint returns; used as a client-side fallback /
 * verification (see FrequencyConfigTest).
 */
object DoseScheduleCalculator {
    /**
     * The number of doses expected on [today] for [frequency] with [timeSlots].
     *
     * - DAILY: one per defined slot (or `timesPerPeriod` when no slots).
     * - WEEKLY: if [FrequencyConfig.specificDays] is set, returns the slot
     *   count only when today is one of those days, else 0. Without specific
     *   days, weekly doses don't land on a particular day → 0.
     * - MONTHLY / PRN: 0 (no fixed daily cadence; PRN is on-demand).
     * - CYCLE: slot count when today is inside an "on" week, else 0.
     */
    fun dosesExpectedToday(
        frequency: FrequencyConfig,
        timeSlots: List<TimeSlot>,
        today: LocalDate = LocalDate.now(),
    ): Int {
        val slotCount = timeSlots.size
        return when (frequency.type) {
            FrequencyType.DAILY ->
                if (slotCount > 0) slotCount else (frequency.timesPerPeriod ?: 1)

            FrequencyType.WEEKLY -> {
                val days = frequency.specificDays
                if (days.isNullOrEmpty()) {
                    0
                } else if (today.dayOfWeek.toCommon() in days) {
                    if (slotCount > 0) slotCount else 1
                } else {
                    0
                }
            }

            FrequencyType.MONTHLY -> 0
            FrequencyType.PRN -> 0

            FrequencyType.CYCLE -> {
                val cycle = frequency.cycle ?: return 0
                if (isInOnWeek(cycle, today)) {
                    if (slotCount > 0) slotCount else 1
                } else {
                    0
                }
            }
        }
    }

    private fun isInOnWeek(cycle: FrequencyConfig.CycleConfig, today: LocalDate): Boolean {
        if (today.isBefore(cycle.startDate)) return false
        val periodWeeks = cycle.onWeeks + cycle.offWeeks
        if (periodWeeks <= 0) return cycle.onWeeks > 0
        val weeksElapsed = ChronoUnit.WEEKS.between(cycle.startDate, today).toInt()
        val weekInCycle = ((weeksElapsed % periodWeeks) + periodWeeks) % periodWeeks
        return weekInCycle < cycle.onWeeks
    }

    private fun java.time.DayOfWeek.toCommon(): DayOfWeek = when (this) {
        java.time.DayOfWeek.MONDAY -> DayOfWeek.MON
        java.time.DayOfWeek.TUESDAY -> DayOfWeek.TUE
        java.time.DayOfWeek.WEDNESDAY -> DayOfWeek.WED
        java.time.DayOfWeek.THURSDAY -> DayOfWeek.THU
        java.time.DayOfWeek.FRIDAY -> DayOfWeek.FRI
        java.time.DayOfWeek.SATURDAY -> DayOfWeek.SAT
        java.time.DayOfWeek.SUNDAY -> DayOfWeek.SUN
    }
}
