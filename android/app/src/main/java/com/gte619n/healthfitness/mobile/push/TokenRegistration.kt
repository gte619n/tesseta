package com.gte619n.healthfitness.mobile.push

import com.google.firebase.messaging.FirebaseMessaging
import com.gte619n.healthfitness.data.sync.DeviceIdProvider
import com.gte619n.healthfitness.data.sync.FcmDeviceRequest
import com.gte619n.healthfitness.data.sync.FcmTokenRequest
import com.gte619n.healthfitness.data.sync.SyncApi
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 6) — FCM device-token registry client (D18).
 *
 * Registers this install's current FCM token with the backend after sign-in
 * (`PUT /api/me/devices/fcm {token, deviceId}`) and deletes it on sign-out
 * (`DELETE /api/me/devices/fcm {deviceId}`). The `deviceId` is the stable
 * per-install id from [DeviceIdProvider] — the same id stamped on outbox writes
 * as `X-HF-Origin-Device`, so the backend can suppress the fan-out back to the
 * originating device.
 *
 * Every method is best-effort: a failure to (de)register a token must never block
 * sign-in or sign-out. The periodic WorkManager floor (D10) is the backstop when
 * FCM is unavailable, and [HfMessagingService.onNewToken] re-registers whenever
 * Firebase rotates the token.
 */
@Singleton
class TokenRegistration @Inject constructor(
    private val api: SyncApi,
    private val deviceIdProvider: DeviceIdProvider,
) {

    /**
     * Fetch the current FCM token and register it. Called after a successful
     * sign-in (the post-bootstrap `SignedIn` path) and from
     * [HfMessagingService.onNewToken]. Swallows all failures.
     */
    suspend fun register() {
        runCatching {
            val token = FirebaseMessaging.getInstance().token.await()
            registerToken(token)
        }
    }

    /** Register an already-known token (the [HfMessagingService.onNewToken] path). */
    suspend fun registerToken(token: String) {
        runCatching {
            api.registerFcmToken(FcmTokenRequest(token = token, deviceId = deviceIdProvider.deviceId()))
        }
    }

    /**
     * Deregister this device on sign-out. Best-effort: if the network is down the
     * token is left server-side (it will simply fan out to a device that no longer
     * reads it); local PHI wipe is the hard guarantee, not this call.
     */
    suspend fun unregister() {
        runCatching {
            api.deleteFcmToken(FcmDeviceRequest(deviceId = deviceIdProvider.deviceId()))
        }
    }
}
