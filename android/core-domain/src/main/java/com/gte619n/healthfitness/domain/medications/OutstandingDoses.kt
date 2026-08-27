package com.gte619n.healthfitness.domain.medications

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * IMPL-21 — pure reducer for the single rolling medication reminder.
 *
 * Answers "what does the user still need to take right now?" at a given clock: the
 * set of **overdue + currently-due** doses (scheduled time already passed, not yet
 * taken), most-overdue first (spec D3/D9). Later-today doses stay hidden until their
 * time. No framework dependencies — the device engine turns this into one notification.
 *
 * This is a different question from [ReminderPlanner] (which answers "when do the next
 * reminders fire?"), so it lives here with its own [DueDose] model that carries the
 * resolved scheduled [DueDose.time] for rendering and overdue ordering. Both share
 * [DoseTimeResolver] and [ReminderPlanner.isDueOn] so scheduling never diverges.
 */
object OutstandingDoses {

    /**
     * Every scheduled dose for [date] regardless of taken status: excludes PRN,
     * muted medications, and medications not due on [date] (frequency/cycle/status).
     * Each dose carries its resolved scheduled time (slot explicit → settings).
     */
    fun scheduledFor(
        medications: List<Medication>,
        settings: ReminderSettings,
        date: LocalDate,
    ): List<DueDose> {
        val result = mutableListOf<DueDose>()
        for (med in medications) {
            if (!settings.enabledFor(med.medicationId)) continue
            if (!ReminderPlanner.isDueOn(med, date)) continue
            for (slot in slotsOf(med)) {
                result += DueDose(
                    medicationId = med.medicationId,
                    name = med.displayName,
                    window = slot.window,
                    dose = slot.dose,
                    unit = med.unit,
                    time = DoseTimeResolver.resolve(med.medicationId, slot, settings),
                )
            }
        }
        return result.sortedBy { it.time }
    }

    /**
     * The outstanding set at [now]: scheduled for `now`'s date, resolved time at or
     * before `now`'s time (due or overdue), and not in [takenToday]. Sorted
     * most-overdue-first (earliest scheduled time first).
     *
     * @param takenToday `(medicationId, window)` pairs already taken for `now`'s date.
     */
    fun outstanding(
        medications: List<Medication>,
        settings: ReminderSettings,
        takenToday: Set<Pair<String, TimeWindow>>,
        now: LocalDateTime,
    ): List<DueDose> {
        if (!settings.enabled) return emptyList()
        val nowTime = now.toLocalTime()
        return scheduledFor(medications, settings, now.toLocalDate())
            .filter { !it.time.isAfter(nowTime) }
            .filter { (it.medicationId to it.window) !in takenToday }
        // scheduledFor already sorts by time (most-overdue first).
    }

    /**
     * The next moment a NOT-yet-outstanding dose crosses into due, strictly after
     * [now] — the time to arm the DUE alarm for (re-alert). Looks at today's
     * later doses and, if none, the earliest dose over the next [days] days. Null
     * when nothing is scheduled ahead.
     */
    fun nextDueTime(
        medications: List<Medication>,
        settings: ReminderSettings,
        now: LocalDateTime,
        days: Int = 8,
    ): LocalDateTime? {
        if (!settings.enabled) return null
        for (offset in 0 until days) {
            val date = now.toLocalDate().plusDays(offset.toLong())
            val next = scheduledFor(medications, settings, date)
                .map { date.atTime(it.time) }
                .filter { it.isAfter(now) }
                .minOrNull()
            if (next != null) return next
        }
        return null
    }

    private fun slotsOf(med: Medication): List<TimeSlot> =
        med.timeSlots.ifEmpty { listOf(TimeSlot(TimeWindow.MORNING, med.dose)) }
}

/** One overdue/due dose in the rolling reminder, carrying its resolved schedule time. */
data class DueDose(
    val medicationId: String,
    val name: String,
    val window: TimeWindow,
    val dose: Double,
    val unit: String,
    val time: LocalTime,
) {
    /** Stable identity for the alert-vs-silent diff and taken/missed keying. */
    val key: String get() = "$medicationId:${window.name}"
}

/**
 * IMPL-21 shared dose-time precedence (spec D2): drug-setup explicit slot time →
 * per-medication settings override → user window time → built-in default. Used by
 * both [OutstandingDoses] and [ReminderPlanner] so the rolling notification and the
 * alarm arming agree on exactly when each dose is due.
 */
object DoseTimeResolver {
    fun resolve(medicationId: String, slot: TimeSlot, settings: ReminderSettings): LocalTime =
        slot.time ?: settings.timeFor(medicationId, slot.window)
}
