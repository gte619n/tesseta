package com.gte619n.healthfitness.mobile.wear

import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// The wear app fires a /auth/refresh-request when its relayed token returns 401
// from the backend. We respond by refreshing the backend access token over
// plain HTTP (ADR-0010) — GoogleAuthRepository's onTokenIssued hook republishes
// the new token to all paired nodes, completing the round-trip. No Credential
// Manager is involved, so nothing flashes on the phone.
//
// We reuse the application-scoped GoogleAuthRepository singleton (wired with the
// AuthApi + the wear token publisher) via a Hilt entry point, rather than
// reconstructing it — a WearableListenerService can't be an @AndroidEntryPoint.
class PhoneRefreshRequestService : WearableListenerService() {

    companion object {
        const val PATH_REFRESH_REQUEST = "/auth/refresh-request"
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface RefreshEntryPoint {
        fun googleAuthRepository(): GoogleAuthRepository
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_REFRESH_REQUEST) return
        val repo = EntryPointAccessors
            .fromApplication(applicationContext, RefreshEntryPoint::class.java)
            .googleAuthRepository()
        scope.launch {
            val result = repo.silentRefresh()
            if (result is AuthState.Failed || result is AuthState.SignedOut) {
                // Wear stays unsigned. Phone UI will prompt on next foreground.
            }
        }
    }
}
