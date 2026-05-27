package com.gte619n.healthfitness.network

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Stamps every outgoing request with the current bearer token, if one is
 * available. We deliberately use `runBlocking` here: the underlying
 * `currentToken()` is a DataStore `first()` call that completes in
 * microseconds once the value is in memory, and OkHttp's interceptor
 * contract is synchronous. A `null`/blank token is treated as "no
 * Authorization header"; the request still goes out, and the server's 401
 * routes into [TokenAuthenticator] for a one-shot refresh + retry.
 */
class AuthInterceptor(
    private val tokenProvider: AuthTokenProvider,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val token = runBlocking { tokenProvider.currentToken() }
        val authorized = if (token.isNullOrBlank()) {
            request
        } else {
            request.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }
        return chain.proceed(authorized)
    }
}
