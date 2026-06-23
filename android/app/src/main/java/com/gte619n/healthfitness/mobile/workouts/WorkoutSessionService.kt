package com.gte619n.healthfitness.mobile.workouts

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers
import com.gte619n.healthfitness.domain.prefs.CoachAudioPreferences
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionRepository
import com.gte619n.healthfitness.mobile.MainActivity
import com.gte619n.healthfitness.mobile.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/**
 * ADR-0012 Decision 6 — the active-session foreground service
 * (`foregroundServiceType="health"`).
 *
 * Pure lifecycle + notification glue: ALL session state lives in the Room
 * draft ([WorkoutSessionRepository], IMPL-AND-17 data layer) and the ephemeral
 * rest timer in [WorkoutSessionTimers]. The service carries **no state of its
 * own** — it observes the newest active draft and renders it into one ongoing
 * notification (day label, current exercise, chronometer-driven elapsed time,
 * or a rest countdown while one is running), so:
 *  - it needs no start extras: [WorkoutSessionForegroundLauncher] starts it
 *    whenever a draft exists, and a `START_STICKY` restart after process death
 *    rehydrates from Room exactly the same way;
 *  - finish/skip/discard need no explicit stop call: deleting the draft row
 *    makes the observed draft go null and the service stops itself.
 *
 * Elapsed/rest display uses the notification chronometer (count-up anchored at
 * `startedAt`, count-down to the rest timer's `endsAt`), so the notification
 * only re-posts on real state changes (sets logged, rest started/ended) —
 * never on a per-second tick.
 */
@AndroidEntryPoint
class WorkoutSessionService : Service() {

    // Lazy on purpose: resolving the repository opens the SQLCipher-backed
    // HfDatabase (loadLibs + Keystore crypto), which must not run on the main
    // thread — onStartCommand resolves it on Dispatchers.IO (same rule as
    // FirstSyncGate / WorkoutSessionBootstrap in MainActivity).
    @Inject lateinit var sessions: dagger.Lazy<WorkoutSessionRepository>

    @Inject lateinit var timers: WorkoutSessionTimers

    @Inject lateinit var coachAudio: CoachAudioPreferences

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    /** Latest "beep on rest end" setting, mirrored so [onRestExpired] reads it cheaply. */
    @Volatile private var restBeepEnabled: Boolean = true

    /** Lazily created on the first beep; routes to STREAM_MUSIC (i.e. headphones). */
    private var toneGenerator: ToneGenerator? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scope.launch { coachAudio.settings.collect { restBeepEnabled = it.restBeep } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Go foreground immediately (the 10s ANR window must not wait on Room);
        // the placeholder is replaced as soon as the draft loads. Re-delivered
        // starts while already watching are no-ops.
        if (watchJob?.isActive != true) {
            goForeground(placeholderNotification())
            watchJob = scope.launch { watchActiveDraft() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        toneGenerator?.release()
        toneGenerator = null
        super.onDestroy()
    }

    /**
     * Observe the newest active draft + the rest timer; re-post the
     * notification on every change and stop once no draft remains. The rest
     * flow re-emits null when the countdown runs out, so the notification
     * falls back to elapsed mode without anyone calling [WorkoutSessionTimers.clearRest].
     */
    private suspend fun watchActiveDraft() {
        val repo = withContext(Dispatchers.IO) { sessions.get() }
        // observeDrafts is newest-started-first; only one session is ever
        // realistically in flight, but if two exist the newest wins.
        combine(repo.observeDrafts().map { it.firstOrNull() }, restWithExpiry()) { draft, rest ->
            draft to rest
        }.collect { (draft, rest) ->
            if (draft == null) {
                stopSession()
            } else {
                postNotification(buildNotification(draft, rest))
            }
        }
    }

    /** The rest timer, re-emitted as null once its countdown runs out. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun restWithExpiry(): Flow<WorkoutSessionTimers.RestTimer?> =
        timers.rest.flatMapLatest { rest ->
            flow {
                emit(rest)
                if (rest != null) {
                    val remaining = Duration.between(Instant.now(), rest.endsAt).toMillis()
                    if (remaining > 0) {
                        // The delay is cancelled (no alert) when the user skips
                        // rest or logs the next set — flatMapLatest tears down this
                        // inner flow — so the buzz only fires on a true expiry.
                        delay(remaining)
                        onRestExpired()
                    }
                    emit(null)
                }
            }
        }

    /** IMPL-COACH: a rest period ran to zero — beep + buzz + a heads-up alert. */
    private fun onRestExpired() {
        if (restBeepEnabled) beep()
        vibrate()
        if (canPostNotifications()) {
            NotificationManagerCompat.from(this)
                .notify(REST_ALERT_NOTIFICATION_ID, restAlertNotification())
        }
    }

    /**
     * Short beep on the music stream so it plays over connected headphones
     * (PR2 audio cue). Best-effort: a failed/again-allocated ToneGenerator must
     * never take down the session, so the whole thing is wrapped defensively.
     */
    private fun beep() {
        runCatching {
            val tone = toneGenerator
                ?: ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME).also { toneGenerator = it }
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_DURATION_MILLIS)
        }
    }

    private fun restAlertNotification(): Notification =
        NotificationCompat.Builder(this, REST_ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(getString(R.string.workout_rest_alert_title))
            .setContentText(getString(R.string.workout_rest_alert_text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            // A glanceable nudge, not a persistent entry beside the ongoing timer.
            .setTimeoutAfter(REST_ALERT_TIMEOUT_MILLIS)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .build()

    /** Haptic buzz on rest end — fires even when notifications are denied. */
    private fun vibrate() {
        val pattern = longArrayOf(0, 250, 150, 250)
        val effect = VibrationEffect.createWaveform(pattern, -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)?.vibrate(effect)
        }
    }

    private fun stopSession() {
        watchJob?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ checks the declared type (and its HIGH_SAMPLING_RATE_SENSORS
            // gate condition) at start time.
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun postNotification(notification: Notification) {
        // The FGS itself runs without POST_NOTIFICATIONS; only shade updates
        // need the grant (requested by the logger UI before starting a session).
        if (canPostNotifications()) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Shown only for the instants before the Room draft loads (or none exists). */
    private fun placeholderNotification(): Notification =
        baseBuilder()
            .setContentTitle(getString(R.string.workout_session_notification_title))
            .build()

    private fun buildNotification(draft: WorkoutSessionDraft, rest: WorkoutSessionTimers.RestTimer?): Notification {
        val content = WorkoutSessionNotificationContent.from(draft, rest, Instant.now())
        return baseBuilder()
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setUsesChronometer(true)
            .apply {
                if (content.countdownToMillis != null) {
                    setChronometerCountDown(true)
                    setWhen(content.countdownToMillis)
                } else {
                    setWhen(checkNotNull(content.elapsedSinceMillis))
                }
            }
            .build()
    }

    private fun baseBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

    /**
     * Idempotent. Two channels: the LOW ongoing-timer channel (never makes a
     * sound) and a HIGH rest-alert channel that peeks with a sound on rest end.
     * The alert channel's own vibration is off — we vibrate manually so the buzz
     * still fires when notifications are denied.
     */
    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val ongoing = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.workout_session_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.workout_session_channel_description) }
        val restAlert = NotificationChannel(
            REST_ALERT_CHANNEL_ID,
            getString(R.string.workout_rest_alert_channel_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = getString(R.string.workout_rest_alert_channel_description)
            enableVibration(false)
        }
        manager.createNotificationChannel(ongoing)
        manager.createNotificationChannel(restAlert)
    }

    companion object {
        const val CHANNEL_ID = "workout_session"
        const val NOTIFICATION_ID = 0x5E55 // "SESS"

        /** IMPL-COACH: HIGH-importance heads-up channel for the rest-end alert. */
        const val REST_ALERT_CHANNEL_ID = "workout_rest_alert"
        const val REST_ALERT_NOTIFICATION_ID = 0x5E56 // distinct from the ongoing timer
        private const val REST_ALERT_TIMEOUT_MILLIS = 10_000L

        /** Rest-end beep loudness (0–100) and length. */
        private const val BEEP_VOLUME = 80
        private const val BEEP_DURATION_MILLIS = 350

        /** Start (or poke) the service. Safe to call repeatedly. */
        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WorkoutSessionService::class.java),
            )
        }
    }
}
