package com.gte619n.healthfitness.data.reminders

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The single fixed id for the one rolling medication reminder (IMPL-21, decision D-6). */
const val MED_REMINDER_NOTIFICATION_ID: Int = 41010

/**
 * IMPL-21: the alarm seam. The engine arms two alarms — the next dose "due" boundary
 * and the next local midnight (missed rollover, spec D5/D8) — behind this interface so
 * the engine's orchestration is unit-testable with a fake scheduler (decision D-5).
 */
interface ReminderScheduler {
    /** (Re)arm the DUE alarm for [atMillis]; firing → [ReminderEngine.onAlarmFired]. */
    fun armDue(atMillis: Long)

    /** Cancel the DUE alarm (nothing scheduled ahead). */
    fun cancelDue()

    /** (Re)arm the MIDNIGHT alarm for [atMillis]; firing → [ReminderEngine.onMidnight]. */
    fun armMidnight(atMillis: Long)
}

/**
 * Production scheduler. Exact alarms when the user granted the special access, falling
 * back to a windowed alarm otherwise. Two distinct request codes / actions so the DUE
 * and MIDNIGHT firings are unambiguous — no time-comparison guesswork at receive time.
 */
@Singleton
class AndroidReminderScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : ReminderScheduler {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun armDue(atMillis: Long) = scheduleExact(atMillis, dueIntent())

    override fun cancelDue() = alarmManager.cancel(dueIntent())

    override fun armMidnight(atMillis: Long) = scheduleExact(atMillis, midnightIntent())

    @SuppressLint("MissingPermission")
    private fun scheduleExact(atMillis: Long, pending: PendingIntent) {
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        if (canExact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, atMillis, FALLBACK_WINDOW_MILLIS, pending)
        }
    }

    private fun dueIntent(): PendingIntent = broadcast(RC_DUE, ReminderEngine.ACTION_REMINDER_FIRE)
    private fun midnightIntent(): PendingIntent = broadcast(RC_MIDNIGHT, ReminderEngine.ACTION_MIDNIGHT)

    private fun broadcast(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val RC_DUE = 41001
        const val RC_MIDNIGHT = 41003
        const val FALLBACK_WINDOW_MILLIS = 15L * 60 * 1000
    }
}
