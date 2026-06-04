package com.gte619n.healthfitness.mobile.auth

import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

// Owns the AuthState for the activity. Bootstraps silently on launch when
// the cache says the user has signed in before; otherwise stays SignedOut
// until interactiveSignIn() is invoked from the SignInScreen.
//
// The moral equivalent of an AuthViewModel, but application-scoped (a Hilt
// @Singleton) so the AuthState survives MainActivity recreation across
// configuration changes. Both collaborators are already in the graph:
// IdTokenCache from NetworkModule and GoogleAuthRepository from
// SettingsAppModule (the latter wired with the full sign-out side effects).
@Singleton
class AuthCoordinator @Inject constructor(
    private val repo: GoogleAuthRepository,
    private val cache: IdTokenCache,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    suspend fun bootstrap() {
        val snapshot = cache.read()
        if (!snapshot.hasSignedIn) {
            _state.value = AuthState.SignedOut
            return
        }
        // Access token still fresh — go straight to SignedIn without any network
        // call. Good for ~1h; TokenAuthenticator refreshes it on the first 401.
        val cachedToken = snapshot.idToken
        if (snapshot.isFresh() && cachedToken != null) {
            _state.value = AuthState.SignedIn(
                userId = "(cached)",
                email = null,
                displayName = null,
                idToken = cachedToken,
            )
        } else {
            // Access token expired — refresh it over plain HTTP (ADR-0010). This
            // is silent: no Credential Manager, no "Signing you in…" UI. If the
            // refresh token is also dead, silentRefresh() returns SignedOut and
            // the UI shows the sign-in screen (a genuine re-auth).
            _state.value = repo.silentRefresh()
        }
    }

    // [activityContext] must be the hosting Activity — Credential Manager needs a
    // window to show the account picker (see GoogleAuthRepository.interactiveSignIn).
    suspend fun interactiveSignIn(activityContext: android.content.Context) {
        _state.value = AuthState.Loading
        _state.value = repo.interactiveSignIn(activityContext)
    }

    suspend fun signOut() {
        repo.signOut()
        _state.value = AuthState.SignedOut
    }
}
