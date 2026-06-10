package com.gte619n.healthfitness.data.workouts.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-0012 Decision 6 — in-memory rest-timer state for the active session.
 *
 * The rest countdown is deliberately ephemeral (process-scoped, not Room): a
 * rest timer that dies with the process is worthless stale by the time the
 * user returns, so unlike the draft itself it does not survive process death.
 * It lives here (not in the logger UI) because ADR-0012 Decision 7 forbids
 * assuming the phone UI is the only writer of the local session: the Compose
 * logger starts/clears it, while `WorkoutSessionService` renders it into the
 * foreground notification — and the Phase 8 Wear mirror will read the same
 * flow.
 *
 * One timer, not one per prescription: only a single session (and thus a
 * single rest) is ever in flight, matching the single foreground notification.
 */
@Singleton
class WorkoutSessionTimers @Inject constructor() {

    /** One running rest countdown: [totalSeconds] long, finishing at [endsAt]. */
    data class RestTimer(val totalSeconds: Int, val endsAt: Instant) {
        /** Whole seconds left on the countdown, clamped at zero. */
        fun remainingSeconds(now: Instant): Long =
            Duration.between(now, endsAt).seconds.coerceAtLeast(0L)

        /** True while there is still time on the clock (an expired timer is dead). */
        fun isRunning(now: Instant): Boolean = endsAt.isAfter(now)
    }

    private val _rest = MutableStateFlow<RestTimer?>(null)

    /** The running rest timer, or null. Consumers must treat an expired timer as null. */
    val rest: StateFlow<RestTimer?> = _rest.asStateFlow()

    /** Start (or restart) the rest countdown. */
    fun startRest(totalSeconds: Int, now: Instant = Instant.now()) {
        _rest.value = RestTimer(totalSeconds, now.plusSeconds(totalSeconds.toLong()))
    }

    /** Stop the countdown (set finished early, or session ended). */
    fun clearRest() {
        _rest.value = null
    }
}
