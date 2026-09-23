package com.gte619n.healthfitness.shared.domain.medications

import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/**
 * IMPL-IOS-01 Phase 1A — extracted from
 * android/core-domain/.../domain/medications/ReminderPlanner.kt
 * (D3: Moshi→kotlinx.serialization).
 *
 * NOTE: not on the original Phase-1A port list, but ported here because it is a
 * pure, required dependency of [OutstandingDoses] (which IS on the list) — that
 * file cannot resolve without `ReminderPlanner.isDueOn`. Flagged for the
 * reviewer.
 *
 * `java.time` swapped for kotlinx-datetime. Translations of note:
 *  - `date.isBefore(x)` / `date.isAfter(x)` → `date < x` / `date > x`
 *  - `ChronoUnit.WEEKS.between(start, date)` → `start.daysUntil(date) / 7`
 *    (both truncate toward zero for whole elapsed weeks)
 *  - `date.lengthOfMonth()` → computed via first-of-next-month minus one day
 *  - `java.time.DayOfWeek` (ISO MON..SUN) mapped to the domain [DayOfWeek]
 *
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
        if (date < med.startDate) return false
        med.endDate?.let { if (date > it) return false }
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
                val weeksSinceStart = cycle.startDate.daysUntil(date).toLong() / 7
                if (weeksSinceStart < 0) return false
                val period = cycle.onWeeks + cycle.offWeeks
                if (period <= 0) return true
                (weeksSinceStart % period) < cycle.onWeeks
            }
        }
    }

    /** Number of days in [this] date's calendar month. */
    private fun LocalDate.lengthOfMonth(): Int {
        val firstOfMonth = LocalDate(year, monthNumber, 1)
        val lastOfMonth = firstOfMonth.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        return lastOfMonth.dayOfMonth
    }

    private fun kotlinx.datetime.DayOfWeek.toDomain(): DayOfWeek = when (this) {
        kotlinx.datetime.DayOfWeek.MONDAY -> DayOfWeek.MON
        kotlinx.datetime.DayOfWeek.TUESDAY -> DayOfWeek.TUE
        kotlinx.datetime.DayOfWeek.WEDNESDAY -> DayOfWeek.WED
        kotlinx.datetime.DayOfWeek.THURSDAY -> DayOfWeek.THU
        kotlinx.datetime.DayOfWeek.FRIDAY -> DayOfWeek.FRI
        kotlinx.datetime.DayOfWeek.SATURDAY -> DayOfWeek.SAT
        kotlinx.datetime.DayOfWeek.SUNDAY -> DayOfWeek.SUN
        else -> DayOfWeek.MON
    }
}
