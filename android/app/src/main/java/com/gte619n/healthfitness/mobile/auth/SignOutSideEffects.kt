package com.gte619n.healthfitness.mobile.auth

import com.gte619n.healthfitness.data.dashboard.RecentActivityCache
import com.gte619n.healthfitness.data.db.DbWipe
import com.gte619n.healthfitness.mobile.push.TokenRegistration
import dagger.Lazy
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
 *  2. **Wipe the encrypted Room DB** (`DbWipe` — close the handle + delete
 *     `hf-offline.db` and its sidecars) so a signed-out device retains no PHI.
 *  3. **Clear the recent-activity feed cache** — a separate DataStore (not the
 *     Room DB), so the wipe above misses it; cleared here so one account's
 *     activity never bleeds into the next on a shared device.
 *
 * Step 2 is the hard guarantee and always runs even if step 1 throws. The whole
 * thing is invoked from `GoogleAuthRepository.signOut()`'s `onSignOut` callback
 * (wired in `SettingsAppModule`), which itself wraps this in a best-effort try so
 * sign-out completes regardless.
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
) {
    suspend fun run() {
        // 1. Best-effort FCM deregistration (never blocks the wipe).
        runCatching { tokenRegistration.get().unregister() }
        // 2. Always wipe local PHI.
        dbWipe.wipe()
        // 3. Clear the recent-feed cache (its own DataStore, not the Room DB).
        runCatching { recentActivityCache.clear() }
    }
}
