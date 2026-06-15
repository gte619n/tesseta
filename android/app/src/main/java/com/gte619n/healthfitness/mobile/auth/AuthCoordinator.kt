package com.gte619n.healthfitness.mobile.auth

import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    // Application-scoped: owns the background token refresh kicked off at launch.
    // Survives MainActivity recreation (this is a @Singleton).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * IMPL-STAB (Workstream C) — offline-first launch. A returning user (one who
     * has signed in before) goes straight to the app on their cached session,
     * even if the access token is stale: reads come from the Room mirror and any
     * API call sends the cached bearer, with [GoogleAuthRepository.silentRefresh]
     * (via TokenAuthenticator) handling the first 401. We never block the UI on a
     * network refresh, and a transient/offline refresh failure never bounces the
     * user to the sign-in screen — only a DEFINITIVE refresh-token rejection does.
     */
    suspend fun bootstrap() {
        val snapshot = cache.read()
        if (!snapshot.hasSignedIn) {
            _state.value = AuthState.SignedOut
            return
        }

        val cachedToken = snapshot.idToken
        if (cachedToken != null) {
            // Render immediately from the cached session — no network on the
            // launch path.
            _state.value = AuthState.SignedIn(
                userId = "(cached)",
                email = null,
                displayName = null,
                idToken = cachedToken,
            )
            // If the access token is stale, refresh it proactively in the
            // background so the next API call already has a fresh bearer — but let
            // only a definitive rejection change the state (see refreshInBackground).
            if (!snapshot.isFresh()) refreshInBackground()
            return
        }

        // Rare: a prior sign-in but no cached access token (e.g. a partial clear).
        // We need one token to operate the API, so refresh once. This is the only
        // path that can block, and only when there's genuinely nothing to show a
        // bearer with. Still silent (no Credential Manager). Anything other than a
        // fresh SignedIn falls back to the sign-in screen.
        _state.value = when (val refreshed = repo.silentRefresh()) {
            is AuthState.SignedIn -> refreshed
            else -> AuthState.SignedOut
        }
    }

    /**
     * Proactive, non-blocking refresh. Only a [AuthState.SignedOut] result — the
     * server definitively rejecting the refresh token (HTTP 401) — flips the user
     * out. A transient/offline failure ([AuthState.Failed]) is left alone: the
     * user stays on their cached session and offline data.
     */
    private fun refreshInBackground() {
        scope.launch {
            when (val refreshed = repo.silentRefresh()) {
                is AuthState.SignedIn -> _state.value = refreshed
                AuthState.SignedOut -> _state.value = AuthState.SignedOut
                else -> Unit // transient failure — keep the cached SignedIn state
            }
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
