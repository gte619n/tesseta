package com.gte619n.healthfitness.data.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gte619n.healthfitness.data.R
import com.gte619n.healthfitness.data.medications.AdherenceRepository
import com.gte619n.healthfitness.data.medications.MedicationRepository
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.PlannedReminder
import com.gte619n.healthfitness.domain.medications.ReminderDose
import com.gte619n.healthfitness.domain.medications.ReminderPlanner
import com.gte619n.healthfitness.domain.medications.TimeWindow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-side medication reminders (IMPL-16 Part A). The backend stores only
 * the configuration; this engine does the rest locally so reminders work
 * offline:
 *
 *  - [replan] computes the upcoming reminders from the medication mirror +
 *    cached settings ([ReminderPlanner]) and sets ONE exact alarm for the
 *    soonest — an alarm chain, re-armed after each firing, boot, time change
 *    and config edit (plus a periodic safety-net worker).
 *  - [onAlarmFired] re-resolves what is due at the fired time (doses logged
 *    in-app since planning drop out), posts a single grouped notification with
 *    per-medication "Took it" actions (≤3 meds) or one "Take all", then chains
 *    the next alarm.
 *  - [onDosesTaken] logs adherence through the offline outbox and re-posts the
 *    notification minus the taken meds — clearing it automatically when every
 *    dose is checked off.
 */
@Singleton
class ReminderEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val medications: MedicationRepository,
    private val adherence: AdherenceRepository,
    private val settings: ReminderSettingsRepository,
) {
    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** Recompute the schedule and arm an exact alarm for the next reminder. */
    suspend fun replan() {
        val next = nextReminder() ?: run {
            alarmManager.cancel(alarmIntent())
            return
        }
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        scheduleExact(at)
    }

    /**
     * An alarm fired (scheduled for [plannedAtMillis]). Re-resolve the doses
     * due at that time, post the grouped notification, then chain the next
     * alarm. Quietly no-ops when everything due was already taken in-app.
     */
    suspend fun onAlarmFired(plannedAtMillis: Long) {
        val plannedAt = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(plannedAtMillis), ZoneId.systemDefault())
        // Re-plan from just before the fire time so the fired reminder itself
        // is the first in the list (plan() returns strictly-after reminders).
        val due = plan(from = plannedAt.minusSeconds(1))
            .firstOrNull { !it.at.isAfter(plannedAt) }
        if (due != null && due.doses.isNotEmpty()) {
            postNotification(plannedAtMillis, due.doses)
        }
        replan()
    }

    /**
     * "Took it" tapped on the notification: log each dose through the offline
     * outbox, then refresh the notification — re-posted minus the taken meds,
     * cancelled once none remain.
     */
    suspend fun onDosesTaken(
        plannedAtMillis: Long,
        taken: List<Pair<String, TimeWindow>>,
        remaining: List<ReminderDose>,
    ) {
        for ((medicationId, window) in taken) {
            runCatching { adherence.logDose(medicationId, window) }
        }
        if (remaining.isEmpty()) {
            notificationManager.cancel(notificationId(plannedAtMillis))
        } else {
            postNotification(plannedAtMillis, remaining)
        }
    }

    // ---- planning -------------------------------------------------------

    private suspend fun nextReminder(): PlannedReminder? =
        plan(from = LocalDateTime.now()).firstOrNull()

    private suspend fun plan(from: LocalDateTime): List<PlannedReminder> {
        val config = settings.getCached()
        if (!config.enabled) return emptyList()
        val meds = runCatching { medications.list(MedicationStatus.ACTIVE) }
            .getOrElse { return emptyList() }
        val takenToday = runCatching { medications.todaysDoses() }
            .getOrElse { emptyList() }
            .filter { it.taken }
            .map { it.medicationId to it.window }
            .toSet()
        return ReminderPlanner.plan(meds, config, takenToday, from)
    }

    // ---- alarms ---------------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun scheduleExact(atMillis: Long) {
        val pending = alarmIntent(atMillis)
        // Dose reminders qualify for exact alarms; fall back to a windowed
        // alarm if the user revoked the special access (Android 12+).
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarmManager.setWindow(
                AlarmManager.RTC_WAKEUP, atMillis, FALLBACK_WINDOW_MILLIS, pending)
        }
    }

    private fun alarmIntent(atMillis: Long = 0L): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java)
            .setAction(ACTION_REMINDER_FIRE)
            .putExtra(EXTRA_PLANNED_AT, atMillis)
        // One fixed requestCode: re-arming always replaces the previous alarm
        // (the chain only ever has a single next firing).
        return PendingIntent.getBroadcast(
            context, RC_ALARM, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    // ---- notification ---------------------------------------------------

    private fun postNotification(plannedAtMillis: Long, doses: List<ReminderDose>) {
        if (!canPostNotifications()) return
        ensureChannel()
        val timeLabel = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(plannedAtMillis), ZoneId.systemDefault())
            .toLocalTime()
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

        val lines = doses.map { "${it.name} — ${formatDose(it.dose)} ${it.unit}" }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder_pill)
            .setContentTitle(
                if (doses.size == 1) "Medication — $timeLabel"
                else "${doses.size} medications — $timeLabel",
            )
            .setContentText(lines.joinToString(", "))
            .setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    lines.forEach { style.addLine(it) }
                },
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setContentIntent(launchAppIntent())

        // Notifications cap out at 3 actions: one "Took it" per medication when
        // they fit, otherwise a single "Take all".
        if (doses.size <= MAX_PER_MED_ACTIONS) {
            doses.forEachIndexed { index, dose ->
                builder.addAction(
                    0, "✓ ${dose.name}",
                    actionIntent(plannedAtMillis, listOf(dose), doses - dose, RC_ACTION_BASE + index),
                )
            }
        } else {
            builder.addAction(
                0, "✓ Take all",
                actionIntent(plannedAtMillis, doses, emptyList(), RC_ACTION_BASE),
            )
        }
        notificationManager.notify(notificationId(plannedAtMillis), builder.build())
    }

    private fun actionIntent(
        plannedAtMillis: Long,
        take: List<ReminderDose>,
        remaining: List<ReminderDose>,
        requestCode: Int,
    ): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(ACTION_DOSE_TAKEN)
            .putExtra(EXTRA_PLANNED_AT, plannedAtMillis)
            .putExtra(EXTRA_TAKE_MEDS, take.map { it.medicationId }.toTypedArray())
            .putExtra(EXTRA_TAKE_WINDOWS, take.map { it.window.name }.toTypedArray())
            .putExtra(EXTRA_REMAINING, encodeDoses(remaining))
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun launchAppIntent(): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context, RC_LAUNCH, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Medication reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Reminders to take your scheduled medications" },
        )
    }

    private fun formatDose(dose: Double): String =
        if (dose == dose.toLong().toDouble()) dose.toLong().toString() else dose.toString()

    companion object {
        const val ACTION_REMINDER_FIRE = "com.gte619n.healthfitness.REMINDER_FIRE"
        const val ACTION_DOSE_TAKEN = "com.gte619n.healthfitness.REMINDER_DOSE_TAKEN"
        const val EXTRA_PLANNED_AT = "plannedAt"
        const val EXTRA_TAKE_MEDS = "takeMeds"
        const val EXTRA_TAKE_WINDOWS = "takeWindows"
        const val EXTRA_REMAINING = "remaining"

        private const val CHANNEL_ID = "medication_reminders"
        private const val MAX_PER_MED_ACTIONS = 3
        private const val RC_ALARM = 41001
        private const val RC_LAUNCH = 41002
        private const val RC_ACTION_BASE = 41100
        private const val FALLBACK_WINDOW_MILLIS = 15L * 60 * 1000

        /** Stable per-firing id so morning/evening reminders coexist in the shade. */
        fun notificationId(plannedAtMillis: Long): Int =
            (plannedAtMillis / 60_000L % Int.MAX_VALUE).toInt()

        /** Compact "med|window|name|dose|unit" rows for the remaining-doses extra. */
        fun encodeDoses(doses: List<ReminderDose>): Array<String> =
            doses.map {
                listOf(it.medicationId, it.window.name, it.name, it.dose.toString(), it.unit)
                    .joinToString("|")
            }.toTypedArray()

        fun decodeDoses(rows: Array<String>?): List<ReminderDose> =
            rows.orEmpty().mapNotNull { row ->
                val parts = row.split("|")
                if (parts.size < 5) return@mapNotNull null
                val window = runCatching { TimeWindow.valueOf(parts[1]) }.getOrNull()
                    ?: return@mapNotNull null
                ReminderDose(
                    medicationId = parts[0],
                    window = window,
                    name = parts[2],
                    dose = parts[3].toDoubleOrNull() ?: 0.0,
                    unit = parts[4],
                )
            }
    }
}
