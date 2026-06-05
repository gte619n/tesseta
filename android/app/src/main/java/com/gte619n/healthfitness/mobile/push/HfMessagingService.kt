package com.gte619n.healthfitness.mobile.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.gte619n.healthfitness.data.sync.SyncScheduler
import com.gte619n.healthfitness.data.sync.SyncSignals
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
 * we deliberately do NOT request `POST_NOTIFICATIONS` and never post a
 * notification — the only effect is a background sync. (If a future feature posts
 * a visible notification, add the runtime permission then.)
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
        // Only react to our sync wakeups. Any other data message is ignored.
        if (message.data["type"] == SYNC_MESSAGE_TYPE) {
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
    }

    override fun onNewToken(token: String) {
        // Firebase rotated the token — push the new value so fan-out stays live.
        scope.launch { tokenRegistration.registerToken(token) }
    }

    companion object {
        const val SYNC_MESSAGE_TYPE = "sync"
    }
}
