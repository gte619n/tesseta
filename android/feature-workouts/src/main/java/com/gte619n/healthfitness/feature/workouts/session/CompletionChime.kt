package com.gte619n.healthfitness.feature.workouts.session

import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember

/**
 * A short "workout complete" chime, played when logging the final set
 * auto-completes the session. Routed to STREAM_MUSIC so it plays over connected
 * headphones — the same stream the rest-end beep in `WorkoutSessionService`
 * uses. Best-effort throughout: a failed [ToneGenerator] must never take down
 * the logger, so allocation and playback are both wrapped defensively and the
 * lambda is a no-op when the generator couldn't be created.
 */
@Composable
fun rememberCompletionChime(): () -> Unit {
    val tone = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, CHIME_VOLUME) }.getOrNull()
    }
    DisposableEffect(tone) {
        onDispose { tone?.release() }
    }
    return remember(tone) {
        {
            // TONE_PROP_ACK is a bright two-note acknowledgement — reads as
            // "done" without needing to sequence tones by hand.
            tone?.let { g -> runCatching { g.startTone(ToneGenerator.TONE_PROP_ACK, CHIME_DURATION_MILLIS) } }
            Unit
        }
    }
}

private const val CHIME_VOLUME = 90
private const val CHIME_DURATION_MILLIS = 400

/**
 * IMPL-COACH: short audio cues for the timed-hold (stretch) coach — a light beep
 * at the halfway / 10-seconds-left marks and a brighter double-beep when the
 * hold target is reached. Same STREAM_MUSIC routing and best-effort guards as
 * [rememberCompletionChime]; the returned lambda takes the ToneGenerator tone id.
 */
@Composable
fun rememberCoachBeep(): (Int) -> Unit {
    val tone = remember {
        runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME) }.getOrNull()
    }
    DisposableEffect(tone) {
        onDispose { tone?.release() }
    }
    return remember(tone) {
        { toneType -> tone?.let { g -> runCatching { g.startTone(toneType, BEEP_DURATION_MILLIS) } }; Unit }
    }
}

private const val BEEP_VOLUME = 85
private const val BEEP_DURATION_MILLIS = 250
