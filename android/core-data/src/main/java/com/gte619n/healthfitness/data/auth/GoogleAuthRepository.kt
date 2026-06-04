package com.gte619n.healthfitness.data.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import retrofit2.HttpException

// Owns authentication for native clients under the backend-issued session-token
// model (ADR-0010).
//
//   interactiveSignIn() — the ONLY path that touches Credential Manager (and so
//     the only one that can show Google's "Signing you in…" UI). It obtains a
//     Google ID token once, then exchanges it at /api/auth/exchange for a
//     backend access + refresh token pair.
//   silentRefresh() — trades the stored refresh token for a fresh access token
//     over plain HTTP. No Credential Manager, no UI. This is what runs on a 401
//     and on launch when the access token has expired, so foregrounding the app
//     never flashes the sign-in UI.
//
// Concurrent 401s are collapsed by [refreshMutex]: the first caller refreshes,
// the rest reuse the access token it just wrote — one network call, no storm.
class GoogleAuthRepository(
    private val context: Context,
    private val cache: IdTokenCache,
    private val authApi: AuthApi,
    private val webOauthClientId: String,
    private val onTokenIssued: suspend (token: String, expiresAt: Long) -> Unit = { _, _ -> },
    // Invoked on sign-out so the caller can deregister FCM + wipe the encrypted
    // offline DB (IMPL-AND-20 D5/D18). Kept as a callback so :core-data's auth
    // layer doesn't take a hard dependency on the DB module wiring.
    private val onSignOut: suspend () -> Unit = {},
) {
    private val manager = CredentialManager.create(context)
    private val refreshMutex = Mutex()

    // First sign-in / re-auth. Credential Manager needs a visible window, so the
    // caller passes the *Activity* context (the application context the repo is
    // built with cannot host UI).
    suspend fun interactiveSignIn(activityContext: Context): AuthState {
        val option = GetGoogleIdOption.Builder()
            // Show the full account picker (not just previously-authorized
            // accounts) — this is the deliberate, user-initiated UI moment.
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webOauthClientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        val googleIdToken = try {
            val response = manager.getCredential(activityContext, request)
            val cred = response.credential
            if (cred !is CustomCredential ||
                cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return AuthState.Failed("unexpected credential type: ${cred.type}")
            }
            GoogleIdTokenCredential.createFrom(cred.data).idToken
        } catch (e: NoCredentialException) {
            // No Google account on the device — guide the user to add one.
            return AuthState.NoAccount
        } catch (e: GetCredentialException) {
            return AuthState.Failed(e.errorMessage?.toString() ?: e.javaClass.simpleName)
        }

        return try {
            val tokens = authApi.exchange("Bearer $googleIdToken")
            persist(tokens)
            signedInFrom(tokens.accessToken)
        } catch (e: Exception) {
            AuthState.Failed("token exchange failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // Silent refresh over HTTP. Single-flight: concurrent callers (e.g. a storm
    // of 401s on foreground) share one refresh and the freshly-stored token.
    suspend fun silentRefresh(): AuthState = refreshMutex.withLock {
        val snapshot = cache.read()
        // Someone else already refreshed while we waited on the lock.
        val current = snapshot.idToken
        if (snapshot.isFresh() && current != null) {
            return signedInFrom(current)
        }
        val refreshToken = snapshot.refreshToken
        if (refreshToken == null || !snapshot.hasUsableRefreshToken) {
            // Nothing to refresh with — the caller must sign in interactively.
            return AuthState.SignedOut
        }
        return try {
            val tokens = authApi.refresh(AuthApi.RefreshRequest(refreshToken))
            persist(tokens)
            signedInFrom(tokens.accessToken)
        } catch (e: HttpException) {
            if (e.code() == 401) {
                // Refresh token revoked/expired — drop the dead session so the UI
                // falls back to interactive sign-in.
                cache.clear()
                AuthState.SignedOut
            } else {
                AuthState.Failed("refresh failed: HTTP ${e.code()}")
            }
        } catch (e: Exception) {
            // Transient (network) failure — keep the session and report Failed so
            // the next attempt can retry.
            AuthState.Failed("refresh failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    suspend fun signOut() {
        val refreshToken = cache.read().refreshToken
        // Best-effort server-side revocation; never blocks the local wipe.
        if (refreshToken != null) {
            try {
                authApi.logout(AuthApi.RefreshRequest(refreshToken))
            } catch (_: Exception) {
            }
        }
        // PHI hygiene (D5): drop the encrypted offline DB before clearing tokens.
        try {
            onSignOut()
        } catch (_: Exception) {
        }
        cache.clear()
        // Clear the Google credential state so the next interactive sign-in shows
        // a fresh picker rather than silently re-selecting the last account.
        try {
            manager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
        }
    }

    private suspend fun persist(tokens: AuthApi.TokenResponse) {
        cache.writeSession(
            accessToken = tokens.accessToken,
            accessExpiresAtEpochSeconds = tokens.accessTokenExpiresAt,
            refreshToken = tokens.refreshToken,
            refreshExpiresAtEpochSeconds = tokens.refreshTokenExpiresAt,
        )
        onTokenIssued(tokens.accessToken, tokens.accessTokenExpiresAt)
    }

    // Build a SignedIn state from the access token, pulling identity claims out
    // of the JWT payload for display. Unverified — the server is the authority;
    // these are presentation-only.
    private fun signedInFrom(accessToken: String): AuthState {
        val claims = decodeClaims(accessToken)
        return AuthState.SignedIn(
            userId = claims.optString("sub", "(session)"),
            email = claims.optString("email", null),
            displayName = claims.optString("name", null),
            idToken = accessToken,
        )
    }

    private fun decodeClaims(jwt: String): JSONObject {
        val parts = jwt.split('.')
        if (parts.size < 2) return JSONObject()
        return try {
            val decoded = android.util.Base64.decode(
                parts[1], android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            JSONObject(String(decoded, Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }
}
