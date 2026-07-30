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

    // ---- whole-session pause (IMPL-COACH: pause on leaving the coach) --------

    @Test
    fun `pause then resume accumulates the away-time`() {
        val timers = WorkoutSessionTimers()

        timers.pause("p", "s", now)
        assertEquals(now, timers.pausedSince.value)
        // While paused, the away-time grows with the clock (elapsed is frozen).
        assertEquals(5_000L, timers.awayMillisAt(now.plusSeconds(5)))

        timers.resume("p", "s", now.plusSeconds(10))
        assertNull(timers.pausedSince.value)
        assertEquals(10_000L, timers.awayMillis.value)
    }

    @Test
    fun `double pause is idempotent`() {
        val timers = WorkoutSessionTimers()

        timers.pause("p", "s", now)
        timers.pause("p", "s", now.plusSeconds(5))

        assertEquals(now, timers.pausedSince.value)
    }

    @Test
    fun `pause banks a running rest and resume restores its remaining time`() {
        val timers = WorkoutSessionTimers()
        timers.startRest(totalSeconds = 90, now = now)

        timers.pause("p", "s", now.plusSeconds(30)) // 60s remaining
        assertNull(timers.rest.value)

        timers.resume("p", "s", now.plusSeconds(50))
        val rest = requireNotNull(timers.rest.value)
        assertEquals(90, rest.totalSeconds)
        assertEquals(60L, rest.remainingSeconds(now.plusSeconds(50)))
    }

    @Test
    fun `resume for a different session drops stale away-time`() {
        val timers = WorkoutSessionTimers()

        timers.pause("p", "s", now)
        timers.resume("p", "other", now.plusSeconds(100))

        assertEquals(0L, timers.awayMillis.value)
        assertNull(timers.pausedSince.value)
    }

    @Test
    fun `clearSession wipes rest and pause state`() {
        val timers = WorkoutSessionTimers()
        timers.startRest(totalSeconds = 90, now = now)
        timers.pause("p", "s", now)
        timers.resume("p", "s", now.plusSeconds(5))

        timers.clearSession()

        assertNull(timers.rest.value)
        assertNull(timers.pausedSince.value)
        assertEquals(0L, timers.awayMillis.value)
    }
}
