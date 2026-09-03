package com.gte619n.healthfitness.feature.workouts.session

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.sin

/**
 * A synthesized referee-style whistle used as the "go" cue at the start of a
 * set — played right before a timed hold's clock starts, and the moment a rep
 * set's rest countdown runs out.
 *
 * There is no whistle asset in the app (the other cues use system tones), so the
 * blast is generated on the fly with [AudioTrack]: a bright ~2.3 kHz tone with a
 * fast vibrato trill and a quick attack/release envelope, which reads as a
 * whistle without shipping a binary. Routed as media/sonification so it plays
 * over connected headphones like the coach beep, and best-effort throughout — a
 * failed allocation or playback must never take down the logger.
 */
@Composable
fun rememberWhistle(): () -> Unit {
    // The PCM is identical every blast, so synthesize it once and replay the buffer.
    val samples = remember { synthesizeWhistle() }
    return remember(samples) {
        {
            runCatching { playOnce(samples) }
            Unit
        }
    }
}

private const val SAMPLE_RATE = 44_100

/**
 * Play the buffer once on a short-lived daemon thread, torn down when the clip
 * has drained. The cue is infrequent, so a fresh track per blast is cheaper to
 * reason about than sharing one across recompositions.
 */
private fun playOnce(samples: ShortArray) {
    thread(isDaemon = true) {
        val track = runCatching {
            AudioTrack(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
                samples.size * 2,
                AudioTrack.MODE_STATIC,
                AudioManager.AUDIO_SESSION_ID_GENERATE,
            )
        }.getOrNull() ?: return@thread
        runCatching {
            track.write(samples, 0, samples.size)
            track.play()
            // The static buffer plays once; wait it out before releasing.
            Thread.sleep(samples.size * 1000L / SAMPLE_RATE + 60L)
        }
        runCatching { track.stop() }
        runCatching { track.release() }
    }
}

/** ~480 ms of a trilling ~2.3 kHz tone: the whistle blast. */
private fun synthesizeWhistle(): ShortArray {
    val total = SAMPLE_RATE * 480 / 1000
    val out = ShortArray(total)
    val baseHz = 2300.0
    val vibratoHz = 18.0     // fast trill
    val vibratoDepth = 90.0  // ± Hz around the base
    val attack = total * 0.06
    val release = total * 0.18
    var phase = 0.0
    for (i in 0 until total) {
        val t = i.toDouble() / SAMPLE_RATE
        val freq = baseHz + vibratoDepth * sin(2 * PI * vibratoHz * t)
        phase += 2 * PI * freq / SAMPLE_RATE
        val env = when {
            i < attack -> i / attack
            i > total - release -> (total - i) / release
            else -> 1.0
        }
        out[i] = (sin(phase) * env * 0.5 * Short.MAX_VALUE).toInt().toShort()
    }
    return out
}
