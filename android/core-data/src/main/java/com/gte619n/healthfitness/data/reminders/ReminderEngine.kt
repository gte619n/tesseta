package com.gte619n.healthfitness.data.reminders

import com.gte619n.healthfitness.data.medications.AdherenceRepository
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.DueDose
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.OutstandingDoses
import com.gte619n.healthfitness.domain.medications.ReminderSettings
import com.gte619n.healthfitness.domain.medications.TimeWindow
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-21 — the single rolling medication reminder engine.
 *
 * Replaces the IMPL-16 per-window notification chain: there is now exactly ONE
 * reminder in the shade at a time, showing only the **overdue + currently-due**
 * doses (computed by the pure [OutstandingDoses] reducer), and it updates live as
 * doses are marked off anywhere (notification action, in-app Today screen via the
 * [ReminderReplanCoordinator] observer, or a remote sync).
 *
 * All entry points funnel through [refresh]:
 *  - [replan] / [onAlarmFired] — recompute + re-post + re-arm the DUE alarm.
 *  - [onDosesTaken] — log the take, then recompute (the notification decrements
 *    silently, or clears when the last dose is checked off).
 *  - [onDismissed] — the user swiped the notification away: remember today's
 *    shown keys so replans stop resurrecting it until a NEW dose crosses into
 *    due (which re-posts and re-alerts the full outstanding list) or midnight.
 *  - [onMidnight] — record the just-ended day's untaken scheduled doses as MISSED
 *    (spec D5/D11), clear the notification, then recompute for the new day.
 *  - [reconcileMissed] — boot/launch catch-up for a skipped midnight (spec D15).
 *
 * [refresh] is deliberately NETWORK-FREE: it runs inside broadcast receivers'
 * ~10s `goAsync` window, and the live `today` fetch it used to make routinely
 * blew that budget on a slow link — the process died before the notification was
 * re-posted, leaving a stale "N medications to take" in the shade after the
 * doses were already logged (the dead "Take all" button). Everything it needs is
 * local: cached settings, the Room mirrors, and the cached `today` projection.
 *
 * Framework I/O is behind [ReminderNotifier] / [ReminderScheduler], durable
 * notification state behind [ReminderStateStore], and time behind [Clock], so
 * the orchestration here is covered by fast JVM tests (decision D-5).
 */
@Singleton
class ReminderEngine @Inject constructor(
    private val medications: MedicationRepository,
    private val adherence: AdherenceRepository,
    private val settings: ReminderSettingsRepository,
    private val notifier: ReminderNotifier,
    private val scheduler: ReminderScheduler,
    private val state: ReminderStateStore,
    private val clock: Clock,
) {

    /** Recompute the single reminder and re-arm the alarms. */
    suspend fun replan() = refresh()

    /** A DUE alarm fired — the same recompute path (a new batch may have crossed into due). */
    suspend fun onAlarmFired() = refresh()

    /**
     * The notification's "✓" action was tapped: log each dose through the offline
     * outbox, then recompute — the reminder decrements silently or clears.
     */
    suspend fun onDosesTaken(taken: List<Pair<String, TimeWindow>>) {
        for ((medicationId, window) in taken) runCatching { adherence.logDose(medicationId, window) }
        refresh()
    }

    /**
     * The user swiped the notification away. Remember every key it was showing;
     * [postOrCancel] then suppresses re-posts until some NEW key joins the
     * outstanding set (or the day rolls over). Never called for our own
     * [ReminderNotifier.cancel] — Android only fires the delete intent on
     * user-driven dismissal.
     */
    suspend fun onDismissed() {
        val today = LocalDateTime.now(clock).toLocalDate()
        state.addDismissedKeys(today, state.postedKeys(today))
    }

    /**
     * Local midnight rolled over: mark the just-ended day's untaken scheduled doses
     * MISSED (synced for stats, spec D11), clear the notification, then recompute for
     * the new day (which re-arms the next midnight alarm).
     */
    suspend fun onMidnight() {
        val endedDay = LocalDateTime.now(clock).toLocalDate().minusDays(1)
        markMissedFor(endedDay)
        notifier.cancel()
        state.clear()
        refresh()
    }

    /**
     * Boot / app-start catch-up (spec D15 / decision D-9): mark yesterday's untaken
     * scheduled doses missed in case the device was off across midnight. Idempotent —
     * [markMissedFor] skips any dose already taken or already missed — then recompute.
     */
    suspend fun reconcileMissed() {
        val yesterday = LocalDateTime.now(clock).toLocalDate().minusDays(1)
        markMissedFor(yesterday)
        refresh()
    }

    // ---- core -----------------------------------------------------------------

    private suspend fun refresh() {
        val now = LocalDateTime.now(clock)
        val config = settings.getCached()
        if (!config.enabled) {
            notifier.cancel()
            state.setPostedKeys(now.toLocalDate(), emptySet())
            scheduler.cancelDue()
            return
        }
        val meds = runCatching { medications.list(MedicationStatus.ACTIVE) }.getOrElse { return }
        val takenToday = takenTodaySet()
        val outstanding = OutstandingDoses.outstanding(meds, config, takenToday, now)
        postOrCancel(outstanding, now.toLocalDate())
        armAlarms(meds, config, now)
    }

    private suspend fun postOrCancel(outstanding: List<DueDose>, today: LocalDate) {
        if (outstanding.isEmpty()) {
            notifier.cancel()
            state.setPostedKeys(today, emptySet())
            return
        }
        val keys = outstanding.map { it.key }.toSet()
        val posted = state.postedKeys(today)
        val dismissed = state.dismissedKeys(today)
        // The alert-vs-silent diff (spec D4), durable across process death: a post
        // re-alerts iff it introduces a key never shown (nor swiped away) today —
        // a new batch crossed into due. A pure decrement/re-post stays silent.
        val newKeys = keys - posted - dismissed
        if (newKeys.isEmpty() && keys.all { it in dismissed }) {
            // Everything still outstanding was swiped away — honor the dismissal
            // instead of resurrecting the notification on every replan trigger.
            // A new batch (or the midnight reset) ends the suppression.
            return
        }
        notifier.post(outstanding, alert = newKeys.isNotEmpty())
        state.setPostedKeys(today, keys)
    }

    private fun armAlarms(meds: List<com.gte619n.healthfitness.domain.medications.Medication>, config: ReminderSettings, now: LocalDateTime) {
        val nextDue = OutstandingDoses.nextDueTime(meds, config, now)
        if (nextDue != null) scheduler.armDue(nextDue.toEpochMillis()) else scheduler.cancelDue()
        // Always keep a midnight alarm so the day rolls over even with no doses due.
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        scheduler.armMidnight(midnight.toEpochMillis())
    }

    private suspend fun markMissedFor(day: LocalDate) {
        val config = settings.getCached()
        val meds = runCatching { medications.list(MedicationStatus.ACTIVE) }.getOrElse { return }
        val scheduled = OutstandingDoses.scheduledFor(meds, config, day)
        if (scheduled.isEmpty()) return
        val recorded = runCatching { adherence.recordedWindowsFor(day) }.getOrElse { emptySet() }
        for (dose in scheduled) {
            if ((dose.medicationId to dose.window) in recorded) continue
            runCatching { adherence.markMissed(dose.medicationId, day, dose.window, dose.dose) }
        }
    }

    /**
     * The `(med:window)` doses to treat as taken for today, as the UNION of two
     * LOCAL sources — no network (see the class doc for why that's load-bearing):
     *  - the CACHED server `today` projection ([MedicationRepository.cachedTodaysDoses],
     *    revalidated whenever the app fetches the checklist) covers takes made on
     *    another device / the web before the last revalidation;
     *  - the adherence mirror ([AdherenceRepository.takenWindowsFor]) covers what
     *    THIS device checked off — including the dose the notification's
     *    "✓"/"Take all" action just logged — plus the day-rows the sync pull lands
     *    for remote takes, which keeps the union current between revalidations.
     *
     * Unioning guarantees a tapped dose clears the reminder while still reflecting
     * remote takes — the bug where "Take all" marked the doses but left the reminder
     * on screen was this taken-set missing the just-logged doses.
     */
    private suspend fun takenTodaySet(): Set<Pair<String, TimeWindow>> {
        val today = LocalDateTime.now(clock).toLocalDate()
        val fromProjection = runCatching { medications.cachedTodaysDoses() }.getOrNull()
            .orEmpty()
            .filter { it.taken }
            .map { it.medicationId to it.window }
        val fromMirror = runCatching { adherence.takenWindowsFor(today) }.getOrElse { emptySet() }
        return fromProjection.toSet() + fromMirror
    }

    private fun LocalDateTime.toEpochMillis(): Long =
        atZone(clock.zone).toInstant().toEpochMilli()

    companion object {
        const val ACTION_REMINDER_FIRE = "com.gte619n.healthfitness.REMINDER_FIRE"
        const val ACTION_MIDNIGHT = "com.gte619n.healthfitness.REMINDER_MIDNIGHT"
        const val ACTION_DOSE_TAKEN = "com.gte619n.healthfitness.REMINDER_DOSE_TAKEN"
        const val ACTION_DISMISSED = "com.gte619n.healthfitness.REMINDER_DISMISSED"
        const val EXTRA_TAKE_MEDS = "takeMeds"
        const val EXTRA_TAKE_WINDOWS = "takeWindows"

        /**
         * Deep-link URI the notification opens, matched by the medications LIST
         * destination's `navDeepLink` and the `MainActivity` `ACTION_VIEW` filter.
         */
        const val DEEP_LINK_DOSE_CHECKLIST = "healthfitness://medications/today"
    }
}
