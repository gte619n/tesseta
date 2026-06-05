package com.gte619n.healthfitness.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped fan-out of inbound FCM "sync" wakeups to FOREGROUND screens that
 * read the backend directly over REST instead of the offline mirror (today:
 * feature-nutrition).
 *
 * The mirror's [SyncEngine] already reacts to a push by pulling the delta into
 * Room, and its [SyncEngine.updatedElsewhere] only fires when a *local* edit is
 * discarded by last-write-wins — so a REST-backed screen has no way to learn
 * that the server changed underneath it (e.g. a photo meal finishing ANALYZING
 * on the backend). Such a screen observes [pushes] and re-fetches.
 *
 * Each emission carries the push's raw `collections` hint (or null) so a
 * subscriber can cheaply filter to what it cares about and still refresh when
 * the hint is absent. No replay: a screen not subscribed when a push lands will
 * pick the change up on its next lifecycle resume anyway.
 */
@Singleton
class SyncSignals @Inject constructor() {
    private val _pushes = MutableSharedFlow<String?>(extraBufferCapacity = 16)

    /** Emits the `collections` hint of each inbound sync push. */
    val pushes: SharedFlow<String?> = _pushes

    /** Called from the FCM service on every sync wakeup. */
    fun onSyncPush(collections: String?) {
        _pushes.tryEmit(collections)
    }
}
