package com.gte619n.healthfitness.shared.presentation.workouts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant

/** kotlinx-datetime has no `plusSeconds`; add via epoch-second arithmetic. */
private fun Instant.plusSeconds(seconds: Long): Instant =
    Instant.fromEpochSeconds(epochSeconds + seconds, nanosecondsOfSecond.toLong())

/**
 * IMPL-IOS-01 Phase 3 Wave D(ii) — THE single self-ticking rest-timer source.
 *
 * This is the anti-drift fix for the rest-timer-dual-state-gate bug (D10). On
 * Android the countdown was an end-anchored `RestTimer(endsAt)` in
 * `WorkoutSessionTimers`, and the composable overlay computed its own remaining
 * seconds off a SEPARATE `now` clock. Two states, two owners → the overlay could
 * vanish while the notification kept ticking.
 *
 * Here there is exactly ONE observable [state]: a [RestTimerState] whose
 * `remainingSeconds` is decremented by ONE coroutine ticker on this holder. Every
 * consumer — the SwiftUI rest overlay, the rest-complete beep trigger, and the
 * Live Activity content-state — reads this same flow. There is no second clock to
 * disagree with. The end-anchor ([endsAt]) is kept internally only so a
 * background/foreground gap resyncs to wall-clock instead of drifting by the
 * number of missed ticks.
 *
 * Process-scoped, in-memory, NOT persisted (ADR-0012 D6): a stale countdown is
 * worthless on resume, so it dies with the VM.
 */
internal class RestTimerHolder(
    private val scope: CoroutineScope,
    private val now: () -> Instant,
) {
    private val _state = MutableStateFlow<RestTimerState?>(null)
    val state: StateFlow<RestTimerState?> = _state.asStateFlow()

    /** Wall-clock instant the running countdown finishes at (null while paused/idle). */
    private var endsAt: Instant? = null
    private var ticker: Job? = null

    fun startRest(totalSeconds: Int) = start(totalSeconds, RestKind.REST)

    fun startGetReady(totalSeconds: Int) = start(totalSeconds, RestKind.GET_READY)

    /**
     * Start a live isometric hold of [totalSeconds] (#275): a wall-clock countdown
     * of the hold's target seconds, its on-screen count-*up* being
     * `totalSeconds - remaining`. Routed through this single self-ticking source so
     * the halfway / ten-second / finish cues fire from ONE place off the wall-clock
     * deadline — they keep sounding when the app is backgrounded mid-plank (where a
     * UI-driven timer would freeze) and can never double-fire ("shadow timer").
     */
    fun startHold(totalSeconds: Int) = start(totalSeconds, RestKind.HOLD)

    private fun start(totalSeconds: Int, kind: RestKind) {
        if (totalSeconds <= 0) { clear(); return }
        endsAt = now().plusSeconds(totalSeconds.toLong())
        _state.value = RestTimerState(
            totalSeconds = totalSeconds,
            remainingSeconds = totalSeconds,
            kind = kind,
            isPaused = false,
        )
        runTicker()
    }

    /** Freeze the countdown, keeping the frozen remaining value (no-op if none/paused). */
    fun pause() {
        val current = _state.value ?: return
        if (current.isPaused) return
        ticker?.cancel()
        endsAt = null
        _state.value = current.copy(remainingSeconds = remainingNow(current), isPaused = true)
    }

    /** Resume a frozen countdown, re-anchoring its end to now + the time left. */
    fun resume() {
        val current = _state.value ?: return
        if (!current.isPaused) return
        endsAt = now().plusSeconds(current.remainingSeconds.toLong())
        _state.value = current.copy(isPaused = false)
        runTicker()
    }

    /** Restart the current countdown from the top, preserving running/paused. */
    fun reset() {
        val current = _state.value ?: return
        if (current.isPaused) {
            _state.value = current.copy(remainingSeconds = current.totalSeconds)
        } else {
            endsAt = now().plusSeconds(current.totalSeconds.toLong())
            _state.value = current.copy(remainingSeconds = current.totalSeconds)
            runTicker()
        }
    }

    /** Stop the countdown (set finished, get-ready skipped, or session ended). */
    fun clear() {
        ticker?.cancel()
        ticker = null
        endsAt = null
        _state.value = null
    }

    fun dispose() = clear()

    /** Remaining seconds derived from the wall-clock end anchor (single source of truth). */
    private fun remainingNow(current: RestTimerState): Int {
        val end = endsAt ?: return current.remainingSeconds
        val secs = (end.epochSeconds - now().epochSeconds)
        return secs.coerceIn(0L, current.totalSeconds.toLong()).toInt()
    }

    /**
     * The one and only ticker. Each second it re-derives remaining from the
     * wall-clock end anchor (so a suspended app catches up instead of drifting)
     * and republishes the SAME state object every consumer reads. Stops itself at
     * zero, leaving the finished state visible for one frame (drives the beep).
     */
    private fun runTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val current = _state.value ?: break
                if (current.isPaused) break
                val remaining = remainingNow(current)
                _state.value = current.copy(remainingSeconds = remaining)
                if (remaining <= 0) break
                delay(1_000)
            }
        }
    }
}
