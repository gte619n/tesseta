package com.gte619n.healthfitness.data.workouts.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ADR-0012 Decision 6 — in-memory countdown-timer state for the active session.
 *
 * The countdown is deliberately ephemeral (process-scoped, not Room): a timer
 * that dies with the process is worthless stale by the time the user returns,
 * so unlike the draft itself it does not survive process death. It lives here
 * (not in the logger UI) because ADR-0012 Decision 7 forbids assuming the phone
 * UI is the only writer of the local session: the Compose logger starts / pauses
 * / clears it, while `WorkoutSessionService` renders it into the foreground
 * notification — and the Phase 8 Wear mirror will read the same flow.
 *
 * One timer, not one per prescription: only a single session (and thus a single
 * countdown) is ever in flight, matching the single foreground notification. The
 * same timer serves two [Kind]s — the between-sets [Kind.REST] countdown for rep
 * exercises and the [Kind.GET_READY] pre-roll before a timed hold — so both share
 * the overlay + notification rendering; only the get-ready pre-roll is pausable
 * (via its Start now / Pause / Reset controls).
 */
@Singleton
class WorkoutSessionTimers @Inject constructor() {

    /** What the running countdown is: a between-sets rest, or a pre-hold get-ready pre-roll. */
    enum class Kind { REST, GET_READY }

    /**
     * One countdown: [totalSeconds] long. While running it finishes at [endsAt];
     * while paused [endsAt] is null and [pausedRemainingSeconds] holds the frozen
     * time left. [kind] tags rest vs. the get-ready pre-roll.
     */
    data class RestTimer(
        val totalSeconds: Int,
        val endsAt: Instant?,
        val pausedRemainingSeconds: Int? = null,
        val kind: Kind = Kind.REST,
    ) {
        /** True while frozen (the get-ready pre-roll was paused); [endsAt] is null. */
        val isPaused: Boolean get() = endsAt == null

        /** Whole seconds left on the countdown, clamped at zero (frozen value while paused). */
        fun remainingSeconds(now: Instant): Long =
            pausedRemainingSeconds?.toLong()
                ?: endsAt?.let { Duration.between(now, it).seconds.coerceAtLeast(0L) }
                ?: 0L

        /** True while running with time still on the clock (an expired or paused timer is not running). */
        fun isRunning(now: Instant): Boolean = endsAt?.isAfter(now) == true
    }

    private val _rest = MutableStateFlow<RestTimer?>(null)

    /** The running countdown, or null. Consumers must treat an expired timer as null. */
    val rest: StateFlow<RestTimer?> = _rest.asStateFlow()

    /** Start (or restart) the between-sets rest countdown. */
    fun startRest(totalSeconds: Int, now: Instant = Instant.now()) {
        _rest.value = RestTimer(totalSeconds, now.plusSeconds(totalSeconds.toLong()), kind = Kind.REST)
    }

    /** Start (or restart) the get-ready pre-roll before a timed hold. */
    fun startGetReady(totalSeconds: Int, now: Instant = Instant.now()) {
        _rest.value = RestTimer(totalSeconds, now.plusSeconds(totalSeconds.toLong()), kind = Kind.GET_READY)
    }

    /** Freeze the current countdown (no-op if already paused or none running). */
    fun pause(now: Instant = Instant.now()) {
        val timer = _rest.value ?: return
        if (timer.isPaused) return
        _rest.value = timer.copy(
            endsAt = null,
            pausedRemainingSeconds = timer.remainingSeconds(now).toInt(),
        )
    }

    /** Resume a frozen countdown, re-anchoring its end to now + the time left (no-op if running). */
    fun resume(now: Instant = Instant.now()) {
        val timer = _rest.value ?: return
        val remaining = timer.pausedRemainingSeconds ?: return
        _rest.value = timer.copy(
            endsAt = now.plusSeconds(remaining.toLong()),
            pausedRemainingSeconds = null,
        )
    }

    /** Restart the current countdown from the top, preserving whether it was running or paused. */
    fun reset(now: Instant = Instant.now()) {
        val timer = _rest.value ?: return
        _rest.value = if (timer.isPaused) {
            timer.copy(pausedRemainingSeconds = timer.totalSeconds)
        } else {
            timer.copy(endsAt = now.plusSeconds(timer.totalSeconds.toLong()))
        }
    }

    /** Stop the countdown (set finished early, get-ready skipped, or session ended). */
    fun clearRest() {
        _rest.value = null
    }

    /** Clear the countdown when a session ends (finish / skip / discard). */
    fun clearSession() {
        _rest.value = null
    }
}
