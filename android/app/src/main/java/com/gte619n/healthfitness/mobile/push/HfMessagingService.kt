package com.gte619n.healthfitness.mobile.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
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
        }
    }

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
    }
}
