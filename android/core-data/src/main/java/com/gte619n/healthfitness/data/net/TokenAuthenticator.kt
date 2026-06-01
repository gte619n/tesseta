package com.gte619n.healthfitness.data.net

import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import com.gte619n.healthfitness.data.auth.IdTokenCache
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
    private val cache: IdTokenCache,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Already retried once — don't loop.
        if (response.request.header(RETRY_HEADER) != null) {
            return null
        }

        val refreshed = runBlocking {
            (repo.silentRefresh() as? AuthState.SignedIn)?.idToken
        } ?: return null

        // silentRefresh() already persists the token via IdTokenCache, but we
        // write through here too so the cache is consistent even if that
        // behavior changes. expiresAt is derived server-side; 0 keeps the
        // existing cache contract (a stale exp just triggers an earlier
        // refresh next time).
        runBlocking { cache.write(refreshed, 0L) }

        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshed")
            .header(RETRY_HEADER, "1")
            .build()
    }

    private companion object {
        const val RETRY_HEADER = "X-HF-Auth-Retry"
    }
}
