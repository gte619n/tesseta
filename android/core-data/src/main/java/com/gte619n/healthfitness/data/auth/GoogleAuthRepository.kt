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
import org.json.JSONObject

// Wraps Credential Manager for Google sign-in.
//
// `interactiveSignIn()` shows the account picker (first-time flow);
// `silentRefresh()` returns a fresh ID token without UI as long as the
// user has previously signed in. The web OAuth client ID is the audience
// — that's Google's documented pattern for Android sign-in.
//
// Listeners (e.g. the phone-to-wear publisher) can subscribe via
// `onTokenIssued` so every successful token fetch can be relayed.
class GoogleAuthRepository(
    private val context: Context,
    private val cache: IdTokenCache,
    private val webOauthClientId: String,
    private val onTokenIssued: suspend (token: String, expiresAt: Long) -> Unit = { _, _ -> },
) {
    private val manager = CredentialManager.create(context)

    suspend fun interactiveSignIn(): AuthState = runSignIn(filterByAuthorized = false)

    suspend fun silentRefresh(): AuthState = runSignIn(filterByAuthorized = true)

    private suspend fun runSignIn(filterByAuthorized: Boolean): AuthState {
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorized)
            .setServerClientId(webOauthClientId)
            .setAutoSelectEnabled(filterByAuthorized)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()

        return try {
            val response = manager.getCredential(context, request)
            val cred = response.credential
            if (cred !is CustomCredential ||
                cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                return AuthState.Failed("unexpected credential type: ${cred.type}")
            }
            val google = GoogleIdTokenCredential.createFrom(cred.data)
            val expires = expiryFromIdToken(google.idToken)
            cache.write(google.idToken, expires)
            onTokenIssued(google.idToken, expires)
            AuthState.SignedIn(
                userId = google.id,
                email = google.id.takeIf { it.contains("@") },
                displayName = google.displayName,
                idToken = google.idToken,
            )
        } catch (e: NoCredentialException) {
            // No credential available. On an interactive sign-in this means the
            // device has no Google account to offer — surface NoAccount so the
            // UI can guide the user to add one. On a silent refresh it just
            // means the user hasn't signed in yet — that's the normal SignedOut.
            if (filterByAuthorized) AuthState.SignedOut else AuthState.NoAccount
        } catch (e: GetCredentialException) {
            AuthState.Failed(e.errorMessage?.toString() ?: e.javaClass.simpleName)
        }
    }

    suspend fun signOut() {
        cache.clear()
        try {
            manager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
            // best-effort; the next sign-in will surface any real failure
        }
    }

    // Reads the `exp` claim out of the JWT payload without verifying — the
    // server is the authority. We only use this locally to decide when to
    // refresh, so a forged value just causes an early refresh attempt.
    private fun expiryFromIdToken(idToken: String): Long {
        val parts = idToken.split('.')
        if (parts.size < 2) return 0L
        val payload = parts[1]
        return try {
            val decoded = android.util.Base64.decode(
                payload, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
            )
            JSONObject(String(decoded, Charsets.UTF_8)).optLong("exp", 0L)
        } catch (_: Exception) {
            0L
        }
    }
}
