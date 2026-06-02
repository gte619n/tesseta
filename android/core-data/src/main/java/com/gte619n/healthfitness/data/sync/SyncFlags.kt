package com.gte619n.healthfitness.data.sync

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.syncFlagsStore by preferencesDataStore("hf-sync-flags")

/**
 * IMPL-AND-20 (Phase 4) — narrow seam the [SyncEngine] writes the kill-switch
 * through, so the engine stays unit-testable on the pure JVM (the test supplies
 * a fake instead of the DataStore-backed [SyncFlags]).
 */
interface KillSwitchSink {
    suspend fun setKillSwitch(on: Boolean)
}

/**
 * IMPL-AND-20 (Phase 4) — persistent sync control flags consumed by Phase 5/6.
 *
 * Currently holds the remote **kill-switch** (D13): when the backend returns
 * `killSwitch=true` in a delta response, the client must drop Room-as-source-of-
 * truth and fall back to live-network reads. The flag is persisted so the
 * fallback survives process death; the read path (Phase 5) observes
 * [killSwitchFlow] to decide whether to serve Room or go straight to the network.
 *
 * It lives in its own DataStore (not the wiped DB and not the auth store) so a
 * Room wipe / sign-out does not silently re-enable DB-of-truth while the backend
 * still wants clients in live-network mode.
 */
@Singleton
class SyncFlags @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) : KillSwitchSink {
    private val killSwitchKey = booleanPreferencesKey("kill_switch")

    /** True ⇒ DB-as-source-of-truth is disabled; clients read live network. */
    val killSwitchFlow: Flow<Boolean> =
        context.syncFlagsStore.data.map { it[killSwitchKey] ?: false }

    suspend fun isKillSwitchOn(): Boolean = killSwitchFlow.first()

    override suspend fun setKillSwitch(on: Boolean) {
        context.syncFlagsStore.edit { it[killSwitchKey] = on }
    }
}
