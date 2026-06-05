package com.gte619n.healthfitness.mobile.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Boundary coverage for the recent-feed relative-time label. (Pure logic — the
 * icon/tone mapping is a trivial `when` over [RecentActivityKind] and touches
 * Compose types, so it's left to the screen-level checks.)
 */
class RecentFeedMappingTest {

    private val now: Instant = Instant.parse("2026-06-05T12:00:00Z")

    @Test
    fun underAMinuteReadsNow() {
        assertEquals("now", relativeTime(now.minusSeconds(30), now))
    }

    @Test
    fun minutesAndHoursAndDays() {
        assertEquals("5m", relativeTime(now.minus(5, ChronoUnit.MINUTES), now))
        assertEquals("59m", relativeTime(now.minus(59, ChronoUnit.MINUTES), now))
        assertEquals("3h", relativeTime(now.minus(3, ChronoUnit.HOURS), now))
        assertEquals("23h", relativeTime(now.minus(23, ChronoUnit.HOURS), now))
        assertEquals("2d", relativeTime(now.minus(2, ChronoUnit.DAYS), now))
        assertEquals("6d", relativeTime(now.minus(6, ChronoUnit.DAYS), now))
    }

    @Test
    fun olderThanAWeekFallsBackToShortDate() {
        val label = relativeTime(now.minus(10, ChronoUnit.DAYS), now)
        // e.g. "MAY 26" — an uppercased "MMM d", not a relative suffix.
        assertTrue("expected a short-date label, got '$label'", label.matches(Regex("^[A-Z]{3,} \\d{1,2}$")))
    }
}
