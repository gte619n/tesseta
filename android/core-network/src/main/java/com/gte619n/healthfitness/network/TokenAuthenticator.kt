package com.gte619n.healthfitness.network

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Handles 401 responses by triggering one silent refresh and resubmitting
 * the original request once. OkHttp's `Authenticator` contract is the
 * documented path for credential renewal (vs. an interceptor, which would
 * also re-run the interceptor chain on the retried request — undesired).
 *
 * The retry budget is exactly one per request, tracked via a header tag so
 * that even concurrent failures cannot recurse into an infinite loop. If
 * the refresh itself fails (`null` return), we surface the original 401 to
 * the caller by returning `null` from `authenticate`.
 */
class TokenAuthenticator(
    private val tokenProvider: AuthTokenProvider,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.header(RETRY_TAG) != null) return null
        val refreshed = runBlocking { tokenProvider.refresh() } ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer $refreshed")
            .header(RETRY_TAG, "1")
            .build()
    }

    private companion object {
        const val RETRY_TAG = "X-HF-Auth-Retry"
    }
}
