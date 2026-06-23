package com.gte619n.healthfitness.feature.workouts.session

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

/**
 * Thin wrapper over Android [TextToSpeech] for the coach's spoken cues (PR2).
 * Initialises asynchronously; [speak] is a no-op until the engine is ready and
 * after [shutdown]. Each utterance flushes the previous one so a quick swipe
 * between exercises doesn't queue stale announcements.
 */
class CoachAnnouncer(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        // Fires asynchronously once the engine binds, so `tts` is assigned by now.
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
            ready = true
        }
    }

    fun speak(text: String) {
        if (ready && text.isNotBlank()) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "coach-cue")
        }
    }

    fun shutdown() {
        ready = false
        tts.stop()
        tts.shutdown()
    }
}

/** Remembers a [CoachAnnouncer] tied to the composition, shutting it down on dispose. */
@Composable
fun rememberCoachAnnouncer(): CoachAnnouncer {
    val context = LocalContext.current
    val announcer = remember { CoachAnnouncer(context) }
    DisposableEffect(Unit) {
        onDispose { announcer.shutdown() }
    }
    return announcer
}
