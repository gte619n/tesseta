package com.gte619n.healthfitness.wear.auth

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Receives Google ID tokens relayed from the paired phone. The phone fires
// this every time it issues or refreshes a token, so the wear app stays in
// lock-step without running its own sign-in dance.
class PhoneTokenSyncService : WearableListenerService() {

    companion object {
        const val PATH_ID_TOKEN = "/auth/id-token"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_ID_TOKEN) return
        val token = String(event.data, Charsets.UTF_8)
        val cache = WearIdTokenCache(applicationContext)
        // A blank payload is the phone's sign-out signal (SignOutSideEffects):
        // drop the cached token so the watch can't keep calling the API as the
        // signed-out account.
        scope.launch { if (token.isBlank()) cache.clear() else cache.write(token) }
    }
}
