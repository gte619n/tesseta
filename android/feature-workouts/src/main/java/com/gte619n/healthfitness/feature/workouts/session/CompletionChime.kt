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
