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

    // ---- whole-session pause (IMPL-COACH: "pause timers when you leave the coach") ----
    //
    // Leaving the coach freezes the session: the elapsed clock stops advancing,
    // the rest countdown is banked, and time spent away is excluded from the
    // workout's duration. Returning resumes exactly where it left off. Pause
    // state is process-scoped like the rest timer (the active session runs a
    // foreground service, so the process stays alive across backgrounding /
    // in-app navigation); it is keyed to a session so a stale pause from a
    // finished workout can never bleed into the next one.

    private val _pausedSince = MutableStateFlow<Instant?>(null)

    /** Non-null while the session is paused (the instant it was paused). */
    val pausedSince: StateFlow<Instant?> = _pausedSince.asStateFlow()

    private val _awayMillis = MutableStateFlow(0L)

    /** Total time already spent paused this session (completed pauses only). */
    val awayMillis: StateFlow<Long> = _awayMillis.asStateFlow()

    private var pausedKey: Pair<String, String>? = null
    /** Rest countdown banked at pause: (original total, seconds still remaining). */
    private var bankedRest: Pair<Int, Int>? = null

    /** Freeze the session: stop the clock and bank any running rest countdown. */
    fun pause(programId: String, scheduledId: String, now: Instant = Instant.now()) {
        if (_pausedSince.value != null) return
        pausedKey = programId to scheduledId
        _pausedSince.value = now
        _rest.value?.let { running ->
            bankedRest = running.totalSeconds to running.remainingSeconds(now).toInt()
            _rest.value = null
        }
    }

    /**
     * Resume a paused session: accumulate the away-time and restore the rest
     * countdown (its original total, re-anchored to the seconds that were left).
     * A resume for a *different* session (the previous one was never cleanly
     * ended) starts fresh rather than banking a stale gap.
     */
    fun resume(programId: String, scheduledId: String, now: Instant = Instant.now()) {
        val key = programId to scheduledId
        val since = _pausedSince.value
        when {
            since != null && pausedKey == key -> {
                _awayMillis.value += Duration.between(since, now).toMillis().coerceAtLeast(0L)
                bankedRest?.let { (total, remaining) ->
                    _rest.value = RestTimer(total, now.plusSeconds(remaining.toLong()))
                }
            }
            pausedKey != null && pausedKey != key -> _awayMillis.value = 0L
        }
        _pausedSince.value = null
        bankedRest = null
        pausedKey = null
    }

    /** Away-time to exclude from elapsed at [now], including any in-progress pause. */
    fun awayMillisAt(now: Instant): Long =
        _awayMillis.value + (_pausedSince.value?.let { Duration.between(it, now).toMillis().coerceAtLeast(0L) } ?: 0L)

    /** Clear rest + pause state when a session ends (finish / skip / discard). */
    fun clearSession() {
        _rest.value = null
        _pausedSince.value = null
        _awayMillis.value = 0L
        pausedKey = null
        bankedRest = null
    }
}
