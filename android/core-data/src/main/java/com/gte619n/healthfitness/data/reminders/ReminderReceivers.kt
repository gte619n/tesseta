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
        if (intent.action != ReminderEngine.ACTION_REMINDER_FIRE) return
        val plannedAt = intent.getLongExtra(ReminderEngine.EXTRA_PLANNED_AT, 0L)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                engine.onAlarmFired(plannedAt)
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
        val plannedAt = intent.getLongExtra(ReminderEngine.EXTRA_PLANNED_AT, 0L)
        val meds = intent.getStringArrayExtra(ReminderEngine.EXTRA_TAKE_MEDS).orEmpty()
        val windows = intent.getStringArrayExtra(ReminderEngine.EXTRA_TAKE_WINDOWS).orEmpty()
        val taken = meds.zip(windows.toList()).mapNotNull { (med, window) ->
            runCatching { TimeWindow.valueOf(window) }.getOrNull()?.let { med to it }
        }
        val remaining = ReminderEngine.decodeDoses(
            intent.getStringArrayExtra(ReminderEngine.EXTRA_REMAINING),
        )
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                engine.onDosesTaken(plannedAt, taken, remaining)
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
                        engine.replan()
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}

/**
 * Periodic safety net (~12h): replans even if every other trigger was missed
 * (e.g. medications changed on another device while this one slept). Also
 * keeps the chain alive across the 48h planning horizon.
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

        /** Idempotent registration (KEEP), mirroring the sync periodic worker. */
        fun register(workManager: WorkManager) {
            val request = PeriodicWorkRequestBuilder<ReminderPlanWorker>(12, TimeUnit.HOURS)
                .build()
            workManager.enqueueUniquePeriodicWork(
                NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
