package com.gte619n.healthfitness.data.net

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * SEC-012: attaches `Authorization: Bearer <accessToken>` to image loads that
 * target the backend (the private meal-photo endpoint
 * `/api/me/nutrition/photo/{id}`, which 302-redirects to a short-lived signed GCS
 * URL). Coil's ImageLoader uses a plain OkHttp client with no auth, so without
 * this the endpoint 401s and every capture-photo meal falls back to the
 * placeholder.
 *
 * Two properties make this safe across the redirect:
 *  - It is a NETWORK interceptor (added via `addNetworkInterceptor`), so it runs
 *    once per hop. The Bearer is added only on the backend hop; OkHttp builds the
 *    redirect follow-up above the network-interceptor chain, from the original
 *    (header-free) request, so the GCS hop never carries it.
 *  - It is host-scoped: the header is added only when the request host matches the
 *    backend. GCS rejects a signed-URL request that also carries an Authorization
 *    header, so this guarantees we never leak the bearer to the bucket even if the
 *    per-hop guarantee above ever changed.
 *
 * Non-backend image loads (drug/gym/equipment imagery on public/CDN hosts, and
 * the raw public GCS `imageUrl` fallback) pass through untouched.
 *
 * [token] is a suspend supplier of the current access token (the same value
 * [AuthInterceptor] sends); it is read per request so a refreshed token is picked
 * up. Kept as a lambda rather than the concrete cache so this stays unit-testable.
 */
class ImageAuthInterceptor(
    private val backendHost: String,
    private val token: suspend () -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (backendHost.isBlank() ||
            !request.url.host.equals(backendHost, ignoreCase = true)
        ) {
            return chain.proceed(request)
        }
        val bearer = runBlocking { token() }
        val authed = if (!bearer.isNullOrBlank()) {
            request.newBuilder().header("Authorization", "Bearer $bearer").build()
        } else {
            request
        }
        return chain.proceed(authed)
    }
}
