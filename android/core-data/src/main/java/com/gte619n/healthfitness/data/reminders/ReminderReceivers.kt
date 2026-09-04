package com.gte619n.healthfitness.data.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.gte619n.healthfitness.domain.medications.TimeWindow
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Receivers for the medication-reminder alarm chain. All work is dispatched
 * via [BroadcastReceiver.goAsync] + a coroutine (the repositories are suspend
 * APIs); each handler is quick — a mirror read, a notification post, and the
 * next alarm.
 */
@AndroidEntryPoint
class ReminderAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: ReminderEngine

    override fun onReceive(context: Context, intent: Intent) {
        // IMPL-21: one receiver, two alarm kinds. DUE recomputes the rolling
        // notification; MIDNIGHT rolls the day over (mark missed + reset).
        val action = intent.action
        if (action != ReminderEngine.ACTION_REMINDER_FIRE && action != ReminderEngine.ACTION_MIDNIGHT) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                if (action == ReminderEngine.ACTION_MIDNIGHT) engine.onMidnight()
                else engine.onAlarmFired()
            } finally {
                pending.finish()
            }
        }
    }
}

/** Handles the notification's "Took it" / "Take all" actions. */
@AndroidEntryPoint
class ReminderActionReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: ReminderEngine

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderEngine.ACTION_DOSE_TAKEN) return
        val meds = intent.getStringArrayExtra(ReminderEngine.EXTRA_TAKE_MEDS).orEmpty()
        val windows = intent.getStringArrayExtra(ReminderEngine.EXTRA_TAKE_WINDOWS).orEmpty()
        val taken = meds.zip(windows.toList()).mapNotNull { (med, window) ->
            runCatching { TimeWindow.valueOf(window) }.getOrNull()?.let { med to it }
        }
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                engine.onDosesTaken(taken)
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Re-arms the alarm chain after events that silently kill or skew it:
 * device reboot, timezone changes and manual clock changes.
 */
@AndroidEntryPoint
class ReminderBootReceiver : BroadcastReceiver() {

    @Inject lateinit var engine: ReminderEngine

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            -> {
                val pending = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                    try {
                        // IMPL-21: on boot also reconcile a possibly-skipped midnight
                        // (mark yesterday's untaken doses missed); this re-plans too.
                        if (intent.action == Intent.ACTION_BOOT_COMPLETED) engine.reconcileMissed()
                        else engine.replan()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

/**
 * Periodic safety net: replans even if every other trigger was missed (medications
 * changed on another device while this one slept) AND — critically — re-arms a DUE
 * alarm that was silently dropped without a reboot. Exact alarms don't survive a
 * force-stop / aggressive OEM battery-kill, and the boot receiver only fires on an
 * actual reboot, so a dropped morning alarm would otherwise not re-arm until the
 * user next opened the app ("morning reminder only showed when I opened the app").
 * An hourly cadence self-heals that within the hour; replan() is cheap and
 * offline-safe (cached settings + local mirrors + re-arm), so the battery cost is
 * negligible. WorkManager still Doze-batches these into maintenance windows.
 */
@HiltWorker
class ReminderPlanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: ReminderEngine,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = runCatching { engine.replan() }
        .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        const val NAME = "hf-reminder-plan"

        /**
         * Idempotent registration. UPDATE (not KEEP) so existing installs adopt the
         * tightened cadence instead of staying on a previously-enqueued interval.
         */
        fun register(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<ReminderPlanWorker>(1, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
