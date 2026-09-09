package com.gte619n.healthfitness.data.workouts.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * ADR-0012 D6 — rest-timer arithmetic for the foreground notification (and the
 * logger UI). The timer is plain process-scoped state; only the clamp/expiry
 * rules need pinning.
 */
class WorkoutSessionTimersTest {

    private val now = Instant.parse("2026-06-10T10:00:00Z")

    @Test
    fun `startRest sets a timer ending totalSeconds from now`() {
        val timers = WorkoutSessionTimers()

        timers.startRest(totalSeconds = 90, now = now)

        val rest = requireNotNull(timers.rest.value)
        assertEquals(90, rest.totalSeconds)
        assertEquals(now.plusSeconds(90), rest.endsAt)
        assertEquals(60L, rest.remainingSeconds(now.plusSeconds(30)))
    }

    @Test
    fun `remaining seconds clamps at zero after expiry`() {
        val rest = WorkoutSessionTimers.RestTimer(totalSeconds = 60, endsAt = now)

        assertEquals(0L, rest.remainingSeconds(now.plusSeconds(5)))
    }

    @Test
    fun `timer is running strictly before its end, dead at and after it`() {
        val rest = WorkoutSessionTimers.RestTimer(totalSeconds = 60, endsAt = now)

        assertTrue(rest.isRunning(now.minusSeconds(1)))
        assertFalse(rest.isRunning(now))
        assertFalse(rest.isRunning(now.plusSeconds(1)))
    }

    @Test
    fun `clearRest drops the timer`() {
        val timers = WorkoutSessionTimers()
        timers.startRest(totalSeconds = 90, now = now)

        timers.clearRest()

        assertNull(timers.rest.value)
    }

    @Test
    fun `startRest replaces a running timer`() {
        val timers = WorkoutSessionTimers()
        timers.startRest(totalSeconds = 90, now = now)

        timers.startRest(totalSeconds = 120, now = now.plusSeconds(10))

        assertEquals(now.plusSeconds(130), requireNotNull(timers.rest.value).endsAt)
    }

    @Test
    fun `clearSession drops the rest timer`() {
        val timers = WorkoutSessionTimers()
        timers.startRest(totalSeconds = 90, now = now)

        timers.clearSession()

        assertNull(timers.rest.value)
    }

    @Test
    fun `startRest tags the timer as a rest countdown`() {
        val timers = WorkoutSessionTimers()

        timers.startRest(totalSeconds = 90, now = now)

        assertEquals(WorkoutSessionTimers.Kind.REST, requireNotNull(timers.rest.value).kind)
    }

    @Test
    fun `startGetReady sets a get-ready pre-roll ending totalSeconds from now`() {
        val timers = WorkoutSessionTimers()

        timers.startGetReady(totalSeconds = 45, now = now)

        val timer = requireNotNull(timers.rest.value)
        assertEquals(WorkoutSessionTimers.Kind.GET_READY, timer.kind)
        assertEquals(45, timer.totalSeconds)
        assertEquals(now.plusSeconds(45), timer.endsAt)
        assertFalse(timer.isPaused)
        assertEquals(35L, timer.remainingSeconds(now.plusSeconds(10)))
    }

    @Test
    fun `pause freezes the remaining time and stops the clock`() {
        val timers = WorkoutSessionTimers()
        timers.startGetReady(totalSeconds = 45, now = now)

        timers.pause(now = now.plusSeconds(10))

        val timer = requireNotNull(timers.rest.value)
        assertTrue(timer.isPaused)
        assertNull(timer.endsAt)
        // Frozen at 35s left, regardless of how much wall-clock time passes.
        assertEquals(35L, timer.remainingSeconds(now.plusSeconds(10)))
        assertEquals(35L, timer.remainingSeconds(now.plusSeconds(600)))
        assertFalse(timer.isRunning(now.plusSeconds(10)))
    }

    @Test
    fun `resume re-anchors the end to now plus the frozen remaining`() {
        val timers = WorkoutSessionTimers()
        timers.startGetReady(totalSeconds = 45, now = now)
        timers.pause(now = now.plusSeconds(10)) // 35s left, frozen

        timers.resume(now = now.plusSeconds(600))

        val timer = requireNotNull(timers.rest.value)
        assertFalse(timer.isPaused)
        assertEquals(now.plusSeconds(635), timer.endsAt)
        assertEquals(35L, timer.remainingSeconds(now.plusSeconds(600)))
    }

    @Test
    fun `reset restarts a running countdown from the top`() {
        val timers = WorkoutSessionTimers()
        timers.startGetReady(totalSeconds = 45, now = now)

        timers.reset(now = now.plusSeconds(30))

        assertEquals(now.plusSeconds(75), requireNotNull(timers.rest.value).endsAt)
    }

    @Test
    fun `reset restarts a paused countdown from the top, still frozen`() {
        val timers = WorkoutSessionTimers()
        timers.startGetReady(totalSeconds = 45, now = now)
        timers.pause(now = now.plusSeconds(10))

        timers.reset(now = now.plusSeconds(20))

        val timer = requireNotNull(timers.rest.value)
        assertTrue(timer.isPaused)
        assertEquals(45L, timer.remainingSeconds(now.plusSeconds(20)))
    }

    @Test
    fun `pause and resume are no-ops when there is no timer`() {
        val timers = WorkoutSessionTimers()

        timers.pause(now)
        timers.resume(now)

        assertNull(timers.rest.value)
    }
}
