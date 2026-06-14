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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.gte619n.healthfitness.data.workouts.session.WorkoutSessionTimers
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
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
                    if (remaining > 0) delay(remaining)
                    emit(null)
                }
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

    /** Idempotent; LOW importance so the ongoing timer never makes a sound. */
    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.workout_session_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.workout_session_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "workout_session"
        const val NOTIFICATION_ID = 0x5E55 // "SESS"

        /** Start (or poke) the service. Safe to call repeatedly. */
        fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WorkoutSessionService::class.java),
            )
        }
    }
}
