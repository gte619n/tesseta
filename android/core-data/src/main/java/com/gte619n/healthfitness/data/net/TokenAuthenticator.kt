package com.gte619n.healthfitness.data.net

import com.gte619n.healthfitness.data.auth.AuthState
import com.gte619n.healthfitness.data.auth.GoogleAuthRepository
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

// IMPL-AND-00 / ADR-0010: silent-refresh-on-401.
//
// When the backend rejects a request with 401, OkHttp invokes this
// Authenticator to (optionally) produce a retried request. We refresh the
// backend access token over plain HTTP (GoogleAuthRepository.silentRefresh) —
// NO Credential Manager, so no "Signing you in…" UI — and replay the original
// request with the new bearer.
//
// Storm safety: a foreground often fires many requests at once, all 401-ing on
// the same expired token. silentRefresh() single-flights via a mutex, so the
// first 401 does the one HTTP refresh and the rest reuse the token it just
// stored — one network call regardless of how many requests raced.
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

        // Do NOT write the token here. silentRefresh() already persisted the new
        // access + rotated refresh token via IdTokenCache.writeSession with the
        // real expiries from the backend. That single write is the source of
        // truth; clobbering it here would force isFresh() false forever.

        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshed")
            .header(RETRY_HEADER, "1")
            .build()
    }

    private companion object {
        const val RETRY_HEADER = "X-HF-Auth-Retry"
    }
}
