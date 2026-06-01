package com.gte619n.healthfitness.data.auth

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.tasks.await

// Sibling to GoogleAuthRepository. Where GoogleAuthRepository owns the
// Credential Manager ID-token sign-in state machine, this class owns the
// Google Identity Services AuthorizationClient flow used to obtain a
// *server auth code* for the Google Health metrics scope. The backend
// exchanges that code for a refresh token (the client secret never reaches
// the device).
//
// The AuthorizationClient is wrapped behind a tiny internal interface so the
// flow is unit-testable without Play Services.
class GoogleHealthScopeRepository @Inject constructor(
    @ApplicationContext context: Context,
    @Named("webOauthClientId") private val webOauthClientId: String,
    private val client: AuthorizationClientWrapper =
        PlayAuthorizationClient(context),
) {

    suspend fun requestHealthAuthorization(): HealthAuthFlow {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(GoogleHealthScopes.METRICS_READ_ONLY)))
            // forceCodeForRefreshToken = true is the GIS equivalent of the
            // web's prompt=consent: it forces a fresh consent so Google
            // issues a new refresh token even if the scope was granted before.
            .requestOfflineAccess(webOauthClientId, true)
            .build()
        return try {
            val result = client.authorize(request)
            val code = result.serverAuthCode
            when {
                code != null -> HealthAuthFlow.Resolved(code)
                result.hasResolution() ->
                    HealthAuthFlow.NeedsUserConsent(result.pendingIntent!!.intentSender)
                else -> HealthAuthFlow.Failed("No server auth code and no resolution")
            }
        } catch (e: ApiException) {
            HealthAuthFlow.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    fun parseConsentResult(data: Intent?): HealthAuthFlow {
        return try {
            val result = client.getAuthorizationResultFromIntent(data)
            result.serverAuthCode?.let { HealthAuthFlow.Resolved(it) }
                ?: HealthAuthFlow.Failed("Consent completed but no server auth code returned")
        } catch (e: ApiException) {
            HealthAuthFlow.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}

sealed interface HealthAuthFlow {
    data class Resolved(val serverAuthCode: String) : HealthAuthFlow
    data class NeedsUserConsent(val intentSender: IntentSender) : HealthAuthFlow
    data class Failed(val cause: String) : HealthAuthFlow
}

// Seam over the final AuthorizationClient so tests can substitute a fake.
interface AuthorizationClientWrapper {
    suspend fun authorize(request: AuthorizationRequest): AuthorizationResult
    fun getAuthorizationResultFromIntent(data: Intent?): AuthorizationResult
}

private class PlayAuthorizationClient(context: Context) : AuthorizationClientWrapper {
    private val delegate = Identity.getAuthorizationClient(context)

    override suspend fun authorize(request: AuthorizationRequest): AuthorizationResult =
        delegate.authorize(request).await()

    override fun getAuthorizationResultFromIntent(data: Intent?): AuthorizationResult =
        delegate.getAuthorizationResultFromIntent(data)
}
