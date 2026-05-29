package com.gte619n.healthfitness.data.net

import com.gte619n.healthfitness.data.auth.IdTokenCache
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

// Injects `Authorization: Bearer <idToken>` on every outgoing request, reading
// the most recent Google ID token from the existing IdTokenCache (IMPL-02).
//
// OkHttp interceptors are synchronous, so we runBlocking on the cache's suspend
// read. The read is a single DataStore lookup (fast, in-memory after first
// hit), and OkHttp already runs this off the main thread on its dispatcher.
// Token refresh on 401 is handled by AuthCoordinator at the UI layer for now;
// a dedicated Authenticator can be added when refresh-on-401 is wired here.
class AuthInterceptor(
    private val cache: IdTokenCache,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { cache.read().idToken }
        val request = chain.request().newBuilder().apply {
            if (!token.isNullOrBlank()) {
                header("Authorization", "Bearer $token")
            }
        }.build()
        return chain.proceed(request)
    }
}
