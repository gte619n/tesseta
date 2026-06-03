package com.gte619n.healthfitness.data.net

import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

// IMPL-AND-00: silent-refresh-on-401.
//
// When the backend rejects a request with 401, OkHttp invokes this
// Authenticator to (optionally) produce a retried request. We silently refresh
// the Google ID token once and replay the original request with the new bearer.
//
// Loop safety: the retried request carries the `X-HF-Auth-Retry` header. If a
// 401 comes back on a request that already has that header, we give up (return
// null) so OkHttp surfaces the 401 instead of refreshing forever.
//
// OkHttp Authenticators are synchronous; silentRefresh() is suspend, so we
// runBlocking on OkHttp's own dispatcher thread (mirrors AuthInterceptor).
class TokenAuthenticator(
    private val repo: GoogleAuthRepository,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Already retried once — don't loop.
        if (response.request.header(RETRY_HEADER) != null) {
            return null
        }

        val refreshed = runBlocking {
            (repo.silentRefresh() as? AuthState.SignedIn)?.idToken
        } ?: return null

        // Do NOT write the token here. silentRefresh() already persisted it via
        // IdTokenCache with the real `exp` claim decoded from the JWT. Writing
        // through with expiresAt = 0 (as this used to) clobbered that expiry,
        // permanently forcing IdTokenCache.isFresh() to false — which made the
        // launch path refresh (and show the "Signing you in…" UI) every time and
        // sent stale tokens on every request. The single write in silentRefresh
        // is the source of truth.

        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshed")
            .header(RETRY_HEADER, "1")
            .build()
    }

    private companion object {
        const val RETRY_HEADER = "X-HF-Auth-Retry"
    }
}
