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
}
