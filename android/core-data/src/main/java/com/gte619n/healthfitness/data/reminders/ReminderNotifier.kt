package com.gte619n.healthfitness.data.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gte619n.healthfitness.data.R
import com.gte619n.healthfitness.domain.medications.DueDose
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-21: the single-notification seam. [ReminderEngine] decides *what* the one
 * rolling reminder should show and *whether* to re-alert; this renders it. Kept
 * behind an interface so the engine's orchestration is unit-testable with a fake
 * notifier (spec D5 / decision D-5).
 */
interface ReminderNotifier {
    /**
     * Post or replace THE single medication reminder with [doses] (already sorted
     * most-overdue-first). [alert] true ⇒ this post may make sound/heads-up (a new
     * batch crossed into due); false ⇒ a silent in-place update (a decrement).
     */
    fun post(doses: List<DueDose>, alert: Boolean)

    /** Remove the reminder (nothing outstanding / day rolled over). */
    fun cancel()
}

/**
 * The production notifier. One fixed notification id ([MED_REMINDER_NOTIFICATION_ID])
 * so there is only ever a single medication reminder in the shade — replacing the old
 * per-firing id. Body is a flat, most-overdue-first list (spec D9); actions are one
 * "✓ <name>" per dose when ≤3 are due, otherwise a single "✓ Take all" (spec D8).
 */
@Singleton
class AndroidReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderNotifier {

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    override fun post(doses: List<DueDose>, alert: Boolean) {
        if (doses.isEmpty()) { cancel(); return }
        if (!canPostNotifications()) return
        ensureChannel()

        val lines = doses.map {
            "${it.name} — ${formatDose(it.dose)} ${it.unit} · ${it.time.format(timeFormat)}"
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_reminder_pill)
            .setContentTitle(
                if (doses.size == 1) "1 medication to take"
                else "${doses.size} medications to take",
            )
            .setContentText(lines.joinToString(", "))
            .setStyle(
                NotificationCompat.InboxStyle().also { style -> lines.forEach { style.addLine(it) } },
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            // Swipeable (spec D6): not ongoing. Auto-cancel stays false so tapping an
            // action keeps it until the engine re-posts/cancels.
            .setOngoing(false)
            .setAutoCancel(false)
            // Alert only when the engine says a new batch crossed into due; a silent
            // decrement re-uses the shade entry without buzzing (spec D4).
            .setOnlyAlertOnce(!alert)
            .setContentIntent(launchAppIntent())

        if (doses.size <= MAX_PER_MED_ACTIONS) {
            doses.forEachIndexed { index, dose ->
                builder.addAction(
                    0, "✓ ${dose.name}",
                    actionIntent(listOf(dose), RC_ACTION_BASE + index),
                )
            }
        } else {
            builder.addAction(0, "✓ Take all", actionIntent(doses, RC_ACTION_BASE))
        }
        notificationManager.notify(MED_REMINDER_NOTIFICATION_ID, builder.build())
    }

    override fun cancel() {
        notificationManager.cancel(MED_REMINDER_NOTIFICATION_ID)
    }

    private fun actionIntent(take: List<DueDose>, requestCode: Int): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(ReminderEngine.ACTION_DOSE_TAKEN)
            .putExtra(ReminderEngine.EXTRA_TAKE_MEDS, take.map { it.medicationId }.toTypedArray())
            .putExtra(ReminderEngine.EXTRA_TAKE_WINDOWS, take.map { it.window.name }.toTypedArray())
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun launchAppIntent(): PendingIntent? {
        val deepLink = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ReminderEngine.DEEP_LINK_DOSE_CHECKLIST))
            .setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolvable = deepLink.resolveActivity(context.packageManager) != null
        val intent = if (resolvable) deepLink
        else context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        return PendingIntent.getActivity(
            context, RC_LAUNCH, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Medication reminders", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Reminders to take your scheduled medications" },
        )
    }

    private fun formatDose(dose: Double): String =
        if (dose == dose.toLong().toDouble()) dose.toLong().toString() else dose.toString()

    private companion object {
        const val CHANNEL_ID = "medication_reminders"
        const val MAX_PER_MED_ACTIONS = 3
        const val RC_LAUNCH = 41002
        const val RC_ACTION_BASE = 41100
    }
}
