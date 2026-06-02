package com.gte619n.healthfitness.data.sync

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.deviceIdStore by preferencesDataStore("hf-device")

/**
 * IMPL-AND-20 (Phase 4) — stable per-install device identifier (D18).
 *
 * A UUID minted once and persisted in its own DataStore. It feeds two things:
 *  - the `X-HF-Origin-Device` header on every outbox write (so the backend can
 *    suppress the FCM fan-out back to the originating device), and
 *  - the `originDeviceId` column on every queued [com.gte619n.healthfitness.data.db.entity.OutboxEntity].
 *
 * It is deliberately NOT in the auth DataStore: it must survive sign-out (the DB
 * is wiped on sign-out for PHI hygiene, but the device identity is not PHI and a
 * stable id across sessions keeps fan-out suppression correct). It is regenerated
 * only on app reinstall / data-clear.
 */
@Singleton
class DeviceIdProvider @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private val key = stringPreferencesKey("device_id")

    @Volatile
    private var cached: String? = null

    /** The persistent device id, minting + persisting one on first access. */
    suspend fun deviceId(): String {
        cached?.let { return it }
        val existing = context.deviceIdStore.data.first()[key]
        if (existing != null) {
            cached = existing
            return existing
        }
        val minted = UUID.randomUUID().toString()
        context.deviceIdStore.edit { it[key] = minted }
        cached = minted
        return minted
    }
}
