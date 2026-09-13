package com.gte619n.healthfitness.mobile.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import android.content.Intent
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gte619n.healthfitness.data.nutrition.NutritionRepository
import com.gte619n.healthfitness.data.sync.SyncScheduler
import com.gte619n.healthfitness.data.sync.SyncSignals
import com.gte619n.healthfitness.mobile.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * IMPL-AND-20 (Phase 6) — FCM client (D1/D10).
 *
 * The backend sends **silent data-only** messages of the shape
 * `{ "type": "sync", "collections": ["medications", ...] }` whenever an in-scope
 * collection changes (with origin-device suppression, so the device that made the
 * change is not woken). This service does NOT read the data itself (ADR-0001: the
 * phone never reads Firestore): it simply enqueues the expedited [SyncWorker] via
 * [SyncScheduler.enqueuePull], which performs the actual REST delta pull.
 *
 * Notifications: these are data-only messages with no user-facing notification, so
 * this service never posts one — the only effect is a background sync. (The
 * `POST_NOTIFICATIONS` runtime permission is requested by the workout logger for
 * the ADR-0012 session notification, not for push.)
 *
 * [onNewToken] re-registers the rotated token so the backend always fans out to a
 * live token.
 */
@AndroidEntryPoint
class HfMessagingService : FirebaseMessagingService() {

    @Inject
    lateinit var scheduler: SyncScheduler

    @Inject
    lateinit var syncSignals: SyncSignals

    @Inject
    lateinit var tokenRegistration: TokenRegistration

    @Inject
    lateinit var nutritionRepository: NutritionRepository

    // Short-lived scope for the fire-and-forget token re-register on rotation.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        when (message.data["type"]) {
            SYNC_MESSAGE_TYPE -> {
                // The `collections` hint is intentionally NOT used to scope the pull:
                // the delta cursor already returns exactly what changed, and treating
                // every wakeup as "pull the delta" keeps the handler trivial and
                // tolerant of fan-out/delta collection-name mismatches (questions #34).
                scheduler.enqueuePull()
                // Also fan the wakeup out to any foreground REST-backed screen (e.g.
                // nutrition), which the mirror pull above doesn't cover. The hint is
                // forwarded so such a screen can filter to its own collections.
                syncSignals.onSyncPush(message.data["collections"])
            }
            // Google Health connection died — prompt the user to reconnect. This
            // FCM message also carries a `notification` block, so the system tray
            // shows it automatically when the app is backgrounded (onMessageReceived
            // is not called then). This branch covers the foreground case, posting
            // the same prompt so it isn't silently lost while the app is open.
            GH_RECONNECT_MESSAGE_TYPE -> postReconnectNotification(message)
            // IMPL-LEFTOVER-01 (D13): the leftover analysis finished. The push data
            // carries only {type}, no date/entryId, so we (a) enqueue a sync pull so
            // the PENDING_REVIEW/REJECTED state lands in the mirror, then (b) post the
            // user-visible notification (this fires in the foreground case; a
            // backgrounded app shows the FCM `notification` block automatically).
            LEFTOVER_REVIEW_MESSAGE_TYPE -> {
                scheduler.enqueuePull()
                syncSignals.onSyncPush("nutrition")
                postLeftoverReviewNotification(message)
            }
            LEFTOVER_RETAKE_MESSAGE_TYPE -> {
                scheduler.enqueuePull()
                syncSignals.onSyncPush("nutrition")
                postLeftoverRetakeNotification(message)
            }
            // Adjust with AI (async): the re-analysis produced a proposal. Sync the
            // PENDING_REVIEW state into the mirror, signal foreground screens, then
            // post the notification (Apply action + a body tap that deep-links into
            // the meal editor's review sheet for the exact entry).
            ADJUST_REVIEW_MESSAGE_TYPE -> {
                scheduler.enqueuePull()
                syncSignals.onSyncPush("nutrition")
                postAdjustReviewNotification(message)
            }
            ADJUST_FAILED_MESSAGE_TYPE -> {
                scheduler.enqueuePull()
                syncSignals.onSyncPush("nutrition")
                postAdjustFailedNotification(message)
            }
        }
    }

    /**
     * Valid leftover result (D13): a notification whose body tap deep-links into
     * the nutrition day (the synced PENDING_REVIEW entry surfaces its "Review
     * leftovers" affordance) AND an Apply action that commits via the apply endpoint.
     * The push carries the target `date`/`entryId` (IL-10), so Apply commits the
     * EXACT entry rather than guessing which pending-review meal the user meant.
     */
    private fun postLeftoverReviewNotification(message: RemoteMessage) {
        if (!canPostNotifications()) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(leftoverChannel())
        val title = message.notification?.title ?: "Leftovers analyzed"
        val body = message.notification?.body ?: "Tap to review what you ate."
        val date = message.data["date"].orEmpty()
        val entryId = message.data["entryId"].orEmpty()
        // Body tap → open the app (the nutrition day surfaces the review affordance).
        val contentIntent = launchAppIntent(RC_LEFTOVER_REVIEW)
        // Apply action → a broadcast that commits the exact entry's proposal in the bg.
        val applyIntent = Intent(this, LeftoverApplyReceiver::class.java)
            .setAction(LeftoverApplyReceiver.ACTION_APPLY)
            .putExtra(LeftoverApplyReceiver.EXTRA_DATE, date)
            .putExtra(LeftoverApplyReceiver.EXTRA_ENTRY_ID, entryId)
        val applyPending = PendingIntent.getBroadcast(
            this,
            RC_LEFTOVER_APPLY,
            applyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, LEFTOVER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync_problem)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Apply", applyPending)
            .build()
        manager.notify(LEFTOVER_NOTIFICATION_ID, notification)
    }

    /** Rejected leftover (D13): tap reopens the leftover camera (via the app). */
    private fun postLeftoverRetakeNotification(message: RemoteMessage) {
        if (!canPostNotifications()) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(leftoverChannel())
        val title = message.notification?.title ?: "Couldn't read leftovers"
        val body = message.notification?.body ?: "Tap to retake the photo."
        val notification = NotificationCompat.Builder(this, LEFTOVER_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync_problem)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(launchAppIntent(RC_LEFTOVER_RETAKE))
            .build()
        manager.notify(LEFTOVER_NOTIFICATION_ID, notification)
    }

    /**
     * Adjust with AI (async): the proposal is ready. Body tap deep-links straight
     * into the meal editor's review sheet for the target entry (via extras the
     * launch Activity routes on); the Apply action commits the exact entry in the
     * background through [AdjustApplyReceiver]. The push carries the target
     * `date`/`entryId` so both target precisely.
     */
    private fun postAdjustReviewNotification(message: RemoteMessage) {
        if (!canPostNotifications()) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(adjustChannel())
        val title = message.notification?.title ?: "Meal re-analyzed"
        val body = message.notification?.body ?: "Tap to review the adjustment."
        val date = message.data["date"].orEmpty()
        val entryId = message.data["entryId"].orEmpty()
        // Body tap → open the app and deep-link to the review sheet for this entry.
        val contentIntent = adjustReviewContentIntent(date, entryId)
        // Apply action → a background broadcast that commits the exact entry.
        val applyIntent = Intent(this, AdjustApplyReceiver::class.java)
            .setAction(AdjustApplyReceiver.ACTION_APPLY)
            .putExtra(AdjustApplyReceiver.EXTRA_DATE, date)
            .putExtra(AdjustApplyReceiver.EXTRA_ENTRY_ID, entryId)
        val applyPending = PendingIntent.getBroadcast(
            this,
            RC_ADJUST_APPLY,
            applyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, ADJUST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync_problem)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .addAction(0, "Apply", applyPending)
            .build()
        manager.notify(ADJUST_NOTIFICATION_ID, notification)
    }

    /** Failed adjustment: tap opens the app so the user can retry from the sheet. */
    private fun postAdjustFailedNotification(message: RemoteMessage) {
        if (!canPostNotifications()) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(adjustChannel())
        val title = message.notification?.title ?: "Couldn't adjust the meal"
        val body = message.notification?.body ?: "Tap to try again."
        val notification = NotificationCompat.Builder(this, ADJUST_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync_problem)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(adjustReviewContentIntent(message.data["date"].orEmpty(), message.data["entryId"].orEmpty()))
            .build()
        manager.notify(ADJUST_NOTIFICATION_ID, notification)
    }

    /**
     * A body-tap intent that opens the launcher activity carrying the extras the
     * Activity reads to deep-link into the adjustment review sheet for (date,
     * entryId). Unlike [launchAppIntent] this attaches routing extras + SINGLE_TOP
     * so a warm app delivers them via onNewIntent.
     */
    private fun adjustReviewContentIntent(date: String, entryId: String): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        launch.putExtra(EXTRA_NAV_DEST, NAV_DEST_ADJUST_REVIEW)
        launch.putExtra(EXTRA_DATE, date)
        launch.putExtra(EXTRA_ENTRY_ID, entryId)
        return PendingIntent.getActivity(
            this, RC_ADJUST_REVIEW, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun adjustChannel() = NotificationChannel(
        ADJUST_CHANNEL_ID,
        "Meal adjustments",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = "Results of an Adjust with AI re-analysis" }

    private fun leftoverChannel() = NotificationChannel(
        LEFTOVER_CHANNEL_ID,
        "Leftovers",
        NotificationManager.IMPORTANCE_HIGH,
    ).apply { description = "Results of a Remove Leftovers analysis" }

    private fun launchAppIntent(requestCode: Int): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(
            this, requestCode, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun postReconnectNotification(message: RemoteMessage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                GH_RECONNECT_CHANNEL_ID,
                "Health connection",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Alerts when a connected health service stops syncing" },
        )
        val title = message.notification?.title ?: "Reconnect Google Health"
        val body = message.notification?.body
            ?: "Your Google Health data stopped syncing. Tap to reconnect."
        // Tap opens the app; the user reconnects from Settings → Google Health.
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                this, RC_GH_RECONNECT, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(this, GH_RECONNECT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sync_problem)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(GH_RECONNECT_NOTIFICATION_ID, notification)
    }

    override fun onNewToken(token: String) {
        // Firebase rotated the token — push the new value so fan-out stays live.
        scope.launch { tokenRegistration.registerToken(token) }
    }

    companion object {
        const val SYNC_MESSAGE_TYPE = "sync"

        // Must match GoogleHealthReconnectNotifier.MESSAGE_TYPE on the backend.
        const val GH_RECONNECT_MESSAGE_TYPE = "gh-reconnect"
        private const val GH_RECONNECT_CHANNEL_ID = "health_connection"
        private const val GH_RECONNECT_NOTIFICATION_ID = 42010
        private const val RC_GH_RECONNECT = 42011

        // IMPL-LEFTOVER-01 (D13): must match LeftoverService.NOTIF_REVIEW / NOTIF_RETAKE.
        const val LEFTOVER_REVIEW_MESSAGE_TYPE = "leftover-review"
        const val LEFTOVER_RETAKE_MESSAGE_TYPE = "leftover-retake"
        private const val LEFTOVER_CHANNEL_ID = "leftovers"
        private const val LEFTOVER_NOTIFICATION_ID = 42020
        private const val RC_LEFTOVER_REVIEW = 42021
        private const val RC_LEFTOVER_RETAKE = 42022
        private const val RC_LEFTOVER_APPLY = 42023

        // Adjust with AI (async): must match MealAdjustmentService.NOTIF_ADJUST_* .
        const val ADJUST_REVIEW_MESSAGE_TYPE = "adjust-review"
        const val ADJUST_FAILED_MESSAGE_TYPE = "adjust-failed"
        private const val ADJUST_CHANNEL_ID = "meal_adjustments"
        private const val ADJUST_NOTIFICATION_ID = 42030
        private const val RC_ADJUST_REVIEW = 42031
        private const val RC_ADJUST_APPLY = 42032

        // Deep-link extras the launcher Activity reads to route a notification body
        // tap to the adjustment review sheet.
        const val EXTRA_NAV_DEST = "com.gte619n.healthfitness.NAV_DEST"
        const val EXTRA_DATE = "com.gte619n.healthfitness.NAV_DATE"
        const val EXTRA_ENTRY_ID = "com.gte619n.healthfitness.NAV_ENTRY_ID"
        const val NAV_DEST_ADJUST_REVIEW = "nutrition-adjust-review"
    }
}
