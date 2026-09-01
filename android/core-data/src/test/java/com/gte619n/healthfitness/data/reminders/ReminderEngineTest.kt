package com.gte619n.healthfitness.data.reminders

import com.gte619n.healthfitness.data.medications.AdherenceRepository
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.DueDose
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.domain.medications.FrequencyType
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.ReminderSettings
import com.gte619n.healthfitness.domain.medications.TimeSlot
import com.gte619n.healthfitness.domain.medications.TimeWindow
import com.gte619n.healthfitness.domain.medications.TodaysDose
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * IMPL-21 — the functional acceptance gate (spec §6.3, F1–F8) run as fast JVM tests
 * with fake framework seams + an adjustable clock (decision D-5). Asserts the rolling
 * decrement, the cross-window re-alert, clear-on-complete, the single-notification
 * invariant, live in-app decrement, midnight-missed rollover, swipe/return, and boot
 * reconciliation — the same behaviors an instrumented shadow test would, more precisely.
 */
class ReminderEngineTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val monday: LocalDate = LocalDate.of(2026, 6, 8)

    private val clock = MutableClock(monday.atTime(6, 0), zone)
    private val notifier = FakeNotifier()
    private val scheduler = FakeScheduler()
    private val medications = mockk<MedicationRepository>()
    private val adherence = mockk<AdherenceRepository>(relaxed = true)
    private val settings = mockk<ReminderSettingsRepository>()

    // Mutable "taken today" the fake todaysDoses reflects, so an in-app mark is observable.
    private val taken = mutableSetOf<Pair<String, TimeWindow>>()
    private val recorded = mutableSetOf<Pair<String, TimeWindow>>()
    // Doses logged to the local adherence mirror on THIS device (the reminder's
    // "✓"/"Take all" path) — distinct from `taken` so a test can model the server
    // `today` projection lagging behind a just-logged dose.
    private val takenLocally = mutableSetOf<Pair<String, TimeWindow>>()

    private val fiveMorning = (1..5).map { med("m$it", "Morning$it", TimeWindow.MORNING, "07:00") }
    private val twoAfternoon = (1..2).map { med("a$it", "Afternoon$it", TimeWindow.AFTERNOON, "13:00") }
    private val allMeds = fiveMorning + twoAfternoon

    private val engine = ReminderEngine(medications, adherence, settings, notifier, scheduler, clock)

    private fun stubRepos(meds: List<Medication> = allMeds) {
        coEvery { settings.getCached() } returns ReminderSettings()
        coEvery { medications.list(MedicationStatus.ACTIVE) } returns meds
        coEvery { medications.todaysDoses() } answers {
            taken.map { (id, w) -> TodaysDose(id, id, w, 1.0, "mg", true, null) }
        }
        coEvery { adherence.recordedWindowsFor(any()) } answers { recorded.toSet() }
        coEvery { adherence.takenWindowsFor(any()) } answers { takenLocally.toSet() }
    }

    // ---- F1: rolling decrement + single-notification invariant ----------------

    @Test
    fun f1_rollingDecrement_isSilentAndSingle() = runTest {
        stubRepos()
        clock.set(monday.atTime(7, 0))
        engine.onAlarmFired()

        assertEquals(1, notifier.activeCount)              // exactly one notification
        assertEquals(5, notifier.lastDoses.size)
        assertTrue("first post alerts", notifier.lastAlert)

        // Mark 4 taken in-app, then refresh (as the adherence observer would).
        (1..4).forEach { taken += "m$it" to TimeWindow.MORNING }
        engine.replan()

        assertEquals(1, notifier.activeCount)
        assertEquals(1, notifier.lastDoses.size)
        assertEquals("m5:MORNING", notifier.lastDoses.single().key)
        assertFalse("decrement is silent", notifier.lastAlert)
        assertTrue("never more than one notification", notifier.maxActive <= 1)
    }

    // ---- F2: cross-window re-alert, most-overdue first ------------------------

    @Test
    fun f2_afternoonBatch_reAlerts_mostOverdueFirst() = runTest {
        stubRepos()
        clock.set(monday.atTime(7, 0)); engine.onAlarmFired()
        (1..4).forEach { taken += "m$it" to TimeWindow.MORNING }
        engine.replan()                                    // decrement to {m5}, silent

        clock.set(monday.atTime(13, 0))
        engine.onAlarmFired()                              // afternoon due alarm

        assertEquals(3, notifier.lastDoses.size)
        assertEquals("m5:MORNING", notifier.lastDoses.first().key)   // most overdue first
        assertTrue("new batch re-alerts", notifier.lastAlert)
    }

    // ---- F3: clear on complete ------------------------------------------------

    @Test
    fun f3_clearsWhenAllTaken() = runTest {
        stubRepos()
        clock.set(monday.atTime(13, 30)); engine.onAlarmFired()
        assertEquals(1, notifier.activeCount)

        allMeds.forEach { taken += it.medicationId to it.timeSlots.first().window }
        engine.replan()

        assertEquals(0, notifier.activeCount)
        assertTrue(notifier.cancelled)
    }

    // ---- F3b: "Take all" clears even when the server projection lags ----------

    @Test
    fun f3b_takeAll_clearsFromLocalMirror_whenProjectionLags() = runTest {
        stubRepos()
        // The reminder's action logs each dose to the local mirror; the server `today`
        // projection is NOT updated (stays empty) — the regression: a just-logged dose
        // the projection doesn't list must still count as taken and clear the reminder.
        coEvery { adherence.logDose(any(), any(), any(), any()) } answers {
            takenLocally += firstArg<String>() to secondArg<TimeWindow>()
        }
        clock.set(monday.atTime(13, 30)); engine.onAlarmFired()
        assertEquals(1, notifier.activeCount)

        engine.onDosesTaken(allMeds.map { it.medicationId to it.timeSlots.first().window })

        assertEquals(0, notifier.activeCount)
        assertTrue(notifier.cancelled)
    }

    // ---- F5: live in-app decrement while "backgrounded" -----------------------

    @Test
    fun f5_inAppMark_decrementsSilently() = runTest {
        stubRepos()
        clock.set(monday.atTime(7, 0)); engine.onAlarmFired()
        val before = notifier.lastDoses.size

        taken += "m1" to TimeWindow.MORNING
        engine.replan()                                    // the coordinator's refresh path

        assertEquals(before - 1, notifier.lastDoses.size)
        assertFalse(notifier.lastAlert)
    }

    // ---- F6: midnight missed rollover -----------------------------------------

    @Test
    fun f6_midnight_marksUntakenMissed_andClears() = runTest {
        stubRepos()
        // End the day with m1 taken, everything else untaken.
        taken += "m1" to TimeWindow.MORNING
        recorded += "m1" to TimeWindow.MORNING           // m1 has a real record
        clock.set(monday.plusDays(1).atStartOfDay())     // 00:00 of the next day
        engine.onMidnight()

        // The 6 untaken scheduled doses of the ended day are marked missed; m1 is not.
        coVerify(exactly = 1) { adherence.markMissed("m2", monday, TimeWindow.MORNING, any()) }
        coVerify(exactly = 1) { adherence.markMissed("a1", monday, TimeWindow.AFTERNOON, any()) }
        coVerify(exactly = 0) { adherence.markMissed("m1", monday, any(), any()) }
        assertTrue(notifier.cancelled)
        // A fresh midnight alarm is armed for the following day.
        assertEquals(monday.plusDays(2).atStartOfDay().toEpoch(), scheduler.lastMidnight)
    }

    // ---- F7: swipe → next due re-alerts ---------------------------------------

    @Test
    fun f7_afterSwipe_nextBatchReAlerts() = runTest {
        stubRepos()
        clock.set(monday.atTime(7, 0)); engine.onAlarmFired()   // posts 5 (alert)
        // User swipes the notification away — external dismissal, engine state unchanged.
        clock.set(monday.atTime(13, 0)); engine.onAlarmFired()  // afternoon crosses into due
        assertTrue("reappears with a re-alert", notifier.lastAlert)
    }

    // ---- F8: boot reconciliation ----------------------------------------------

    @Test
    fun f8_bootReconcile_marksYesterdayMissed() = runTest {
        stubRepos()
        clock.set(monday.plusDays(1).atTime(9, 0))       // "now" is the day after
        engine.reconcileMissed()

        // Yesterday (monday) had 7 scheduled doses, none recorded → all marked missed.
        coVerify(exactly = 1) { adherence.markMissed("m3", monday, TimeWindow.MORNING, any()) }
        coVerify(exactly = 1) { adherence.markMissed("a2", monday, TimeWindow.AFTERNOON, any()) }
    }

    // ---- alarm arming ---------------------------------------------------------

    @Test
    fun armsNextDueAndMidnight() = runTest {
        stubRepos()
        clock.set(monday.atTime(6, 0)); engine.replan()
        assertEquals(monday.atTime(7, 0).toEpoch(), scheduler.lastDue)
        assertEquals(monday.plusDays(1).atStartOfDay().toEpoch(), scheduler.lastMidnight)
    }

    @Test
    fun disabledSettings_cancelEverything() = runTest {
        coEvery { settings.getCached() } returns ReminderSettings(enabled = false)
        coEvery { medications.list(any()) } returns allMeds
        coEvery { medications.todaysDoses() } returns emptyList()
        clock.set(monday.atTime(8, 0)); engine.replan()
        assertTrue(notifier.cancelled)
        assertTrue(scheduler.dueCancelled)
    }

    // ---- helpers --------------------------------------------------------------

    private fun LocalDateTime.toEpoch(): Long = atZone(zone).toInstant().toEpochMilli()

    private fun med(id: String, name: String, window: TimeWindow, time: String) = Medication(
        medicationId = id, drugId = null, drug = null, customName = name,
        status = MedicationStatus.ACTIVE, dose = 1.0, unit = "mg",
        frequency = FrequencyConfig(FrequencyType.DAILY),
        timeSlots = listOf(TimeSlot(window, 1.0, java.time.LocalTime.parse(time))),
        protocolId = null, notes = null, prescribedBy = null,
        startDate = LocalDate.of(2026, 1, 1), endDate = null,
        discontinueReason = null, discontinueNotes = null,
        correlatedMarkers = emptyList(), adherence = null,
    )

    private class MutableClock(private var now: LocalDateTime, private val zone: ZoneId) : Clock() {
        fun set(t: LocalDateTime) { now = t }
        override fun getZone(): ZoneId = zone
        override fun withZone(z: ZoneId): Clock = MutableClock(now, z)
        override fun instant(): Instant = now.atZone(zone).toInstant()
    }

    private class FakeNotifier : ReminderNotifier {
        var lastDoses: List<DueDose> = emptyList()
        var lastAlert = false
        var activeCount = 0
        var maxActive = 0
        var cancelled = false
        override fun post(doses: List<DueDose>, alert: Boolean) {
            lastDoses = doses; lastAlert = alert; cancelled = false
            activeCount = 1; maxActive = maxOf(maxActive, activeCount)
        }
        override fun cancel() { activeCount = 0; cancelled = true }
    }

    private class FakeScheduler : ReminderScheduler {
        var lastDue: Long? = null
        var lastMidnight: Long? = null
        var dueCancelled = false
        override fun armDue(atMillis: Long) { lastDue = atMillis }
        override fun cancelDue() { dueCancelled = true }
        override fun armMidnight(atMillis: Long) { lastMidnight = atMillis }
    }
}
