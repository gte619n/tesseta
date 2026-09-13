package com.gte619n.healthfitness.mobile.auth

import android.content.Context
import coil.ImageLoader
import coil.annotation.ExperimentalCoilApi
import com.gte619n.healthfitness.data.dashboard.RecentActivityCache
import com.gte619n.healthfitness.data.db.DbWipe
import com.gte619n.healthfitness.data.medications.TodaysDosesCache
import com.gte619n.healthfitness.data.net.HttpCacheWipe
import com.gte619n.healthfitness.data.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.mobile.push.TokenRegistration
import com.gte619n.healthfitness.mobile.wear.PhoneTokenPublisher
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 6) — the single consolidated sign-out side-effect hook (#12).
 *
 * Every sign-out (today only the Settings path, but this is wired on the one
 * application-scoped [com.gte619n.healthfitness.data.auth.GoogleAuthRepository]
 * instance, so any future caller of `signOut()` inherits it) must, in order:
 *
 *  1. **Deregister the FCM token** (`DELETE /api/me/devices/fcm`) so the backend
 *     stops fanning out to this device. Best-effort — a network failure here must
 *     not block the wipe.
 *  2. **Tell the paired watch to drop its relayed token** — a blank payload on
 *     the `/auth/id-token` path is the sign-out signal (`PhoneTokenSyncService`
 *     clears `WearIdTokenCache` on it). Best-effort: an unreachable watch keeps
 *     a token that expires on its own and can never be refreshed.
 *  3. **Wipe all local per-user state** via [wipeLocalData].
 *
 * [wipeLocalData] is also invoked on its own by [AuthCoordinator] when an
 * interactive sign-in resolves to a *different* account than the one whose
 * data is on this device — reachable without an intervening sign-out when the
 * previous session died server-side (refresh token rejected → cache cleared,
 * but no `onSignOut`). Without that wipe, user B would land on user A's
 * mirrored PHI. That path must skip steps 1–2 (the old session is already
 * gone; the new account re-registers FCM and republishes to Wear itself).
 */
@Singleton
class SignOutSideEffects @Inject constructor(
    // Lazy: TokenRegistration pulls in SyncApi → Retrofit → OkHttp → the
    // TokenAuthenticator, which itself depends on GoogleAuthRepository — the very
    // repository whose `onSignOut` calls us. Deferring construction to first use
    // (sign-out time) breaks that Hilt dependency cycle. It also means we never
    // build the network stack just to register the hook.
    private val tokenRegistration: Lazy<TokenRegistration>,
    private val dbWipe: DbWipe,
    private val recentActivityCache: RecentActivityCache,
    // offline-fix: the full Today's Doses screen's own DataStore cache (not the Room DB).
    private val todaysDosesCache: TodaysDosesCache,
    private val unitPreferences: UnitPreferencesRepository,
    private val httpCacheWipe: HttpCacheWipe,
    private val imageLoader: ImageLoader,
    @ApplicationContext private val context: Context,
) {
    suspend fun run() {
        // 1. Best-effort FCM deregistration (never blocks the wipe).
        runCatching { tokenRegistration.get().unregister() }
        // 2. Best-effort Wear token clear (blank payload = sign-out signal).
        runCatching { PhoneTokenPublisher(context).publish("") }
        // 3. Always wipe local PHI + per-user caches.
        wipeLocalData()
    }

    /**
     * The local-wipe half of sign-out: everything the signed-out (or switched-
     * away-from) account left on disk. Each step past the Room wipe is
     * best-effort so one failing cache never blocks the rest.
     */
    @OptIn(ExperimentalCoilApi::class)
    suspend fun wipeLocalData() {
        // The hard guarantee: drop the encrypted Room mirror (PHI).
        dbWipe.wipe()
        // Side caches living outside the Room DB, each their own DataStore.
        runCatching { recentActivityCache.clear() }
        runCatching { todaysDosesCache.clear() }
        runCatching { unitPreferences.clear() }
        // OkHttp's shared disk cache — images/media fetched under the old session.
        runCatching { httpCacheWipe.wipe() }
        // Coil's caches hold meal photos and other user imagery.
        withContext(Dispatchers.IO) {
            runCatching { imageLoader.memoryCache?.clear() }
            runCatching { imageLoader.diskCache?.clear() }
        }
    }
}
