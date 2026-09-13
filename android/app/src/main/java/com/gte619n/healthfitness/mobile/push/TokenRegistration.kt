package com.gte619n.healthfitness.mobile.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.gte619n.healthfitness.data.sync.DeviceIdProvider
import com.gte619n.healthfitness.data.sync.FcmDeviceRequest
import com.gte619n.healthfitness.data.sync.FcmTokenRequest
import com.gte619n.healthfitness.data.sync.SyncApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * IMPL-AND-20 (Phase 6) — FCM device-token registry client (D18).
 *
 * Registers this install's current FCM token with the backend on every signed-in
 * launch (`PUT /api/me/devices/fcm {token, deviceId}`) and deletes it on
 * sign-out (`DELETE /api/me/devices/fcm {deviceId}`). The `deviceId` is the
 * stable per-install id from [DeviceIdProvider] — the same id stamped on outbox
 * writes as `X-HF-Origin-Device`, so the backend can suppress the fan-out back
 * to the originating device.
 *
 * Every method is best-effort: a failure to (de)register a token must never
 * block sign-in or sign-out. But best-effort is NOT silent — an unregistered
 * device receives no pushes at all (notifications AND the silent sync wakeups),
 * and a swallowed token-fetch failure is exactly how the 2026-09 FCM
 * cert-mismatch outage stayed invisible for months. So [register] retries a few
 * times with backoff and every failure is logged, distinguishing the token
 * *fetch* (Firebase Installations — fails when e.g. the build's signing cert is
 * not registered with the Firebase app) from the backend *registration* call.
 * The periodic WorkManager floor (D10) is the sync backstop when FCM is
 * unavailable, and [HfMessagingService.onNewToken] re-registers whenever
 * Firebase rotates the token.
 */
@Singleton
class TokenRegistration @Inject constructor(
    private val api: SyncApi,
    private val deviceIdProvider: DeviceIdProvider,
) {

    /**
     * Fetch the current FCM token and register it, retrying transient failures
     * with backoff. Called after a successful sign-in and on every signed-in
     * launch. Never throws (cancellation excepted).
     */
    suspend fun register() {
        var delayMs = FIRST_RETRY_DELAY_MS
        repeat(REGISTER_ATTEMPTS) { attempt ->
            if (registerOnce(attempt)) {
                return
            }
            if (attempt < REGISTER_ATTEMPTS - 1) {
                delay(delayMs)
                delayMs *= BACKOFF_FACTOR
            }
        }
        Log.w(
            TAG,
            "FCM token registration gave up after $REGISTER_ATTEMPTS attempts; " +
                "push (notifications + sync wakeups) stays dead until the next launch",
        )
    }

    /** Register an already-known token (the [HfMessagingService.onNewToken] path). */
    suspend fun registerToken(token: String) {
        try {
            api.registerFcmToken(FcmTokenRequest(token = token, deviceId = deviceIdProvider.deviceId()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "FCM token registration call failed", e)
        }
    }

    /**
     * Deregister this device on sign-out. Best-effort: if the network is down the
     * token is left server-side (it will simply fan out to a device that no longer
     * reads it); local PHI wipe is the hard guarantee, not this call.
     */
    suspend fun unregister() {
        try {
            api.deleteFcmToken(FcmDeviceRequest(deviceId = deviceIdProvider.deviceId()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "FCM token deregistration failed (stale token left server-side)", e)
        }
    }

    /** One fetch+register attempt. Returns true on success; logs which half failed. */
    private suspend fun registerOnce(attempt: Int): Boolean {
        val label = "attempt ${attempt + 1}/$REGISTER_ATTEMPTS"
        val token = try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Firebase Installations / registration API failure — e.g. this
            // build's signing cert is not registered with the Firebase app
            // (the API key rejects the Installations call with a 403).
            Log.w(TAG, "FCM token fetch failed ($label)", e)
            return false
        }
        return try {
            api.registerFcmToken(FcmTokenRequest(token = token, deviceId = deviceIdProvider.deviceId()))
            if (attempt > 0) {
                Log.i(TAG, "FCM token registered after retry ($label)")
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "FCM token registration call failed ($label)", e)
            false
        }
    }

    private companion object {
        const val TAG = "TokenRegistration"
        const val REGISTER_ATTEMPTS = 3
        const val FIRST_RETRY_DELAY_MS = 5_000L
        const val BACKOFF_FACTOR = 4
    }
}
