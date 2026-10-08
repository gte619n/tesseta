package com.gte619n.healthfitness.shared.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * IMPL-IOS-01 Phase 1C — the ONE authenticated Ktor client the shared REST
 * repositories share. It reuses the EXISTING backend endpoints (Android/web hit
 * the same ones); only the client call is new on iOS.
 *
 *  - base URL + relative paths (`client.get("api/me")`),
 *  - kotlinx.serialization JSON (shared @Serializable DTOs decode directly),
 *  - `Authorization: Bearer <session access token>` injected per-request from
 *    [SessionTokenProvider] — the SAME session login establishes (the iOS impl
 *    reads the Keychain). Pulling the token per-request means a silent refresh
 *    is picked up without rebuilding the client.
 */
object ApiClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** `baseUrl` MUST end with "/" so relative request paths resolve correctly. */
    fun create(baseUrl: String, tokens: SessionTokenProvider): HttpClient = HttpClient {
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
        // The SSE transport ([KtorSseClient]) sets a per-request `socketTimeoutMillis`
        // to cap a half-open stream that never emits and never closes (#282). The
        // per-request `timeout { }` block requires this plugin installed on the
        // client; no default timeouts are set here so ordinary REST calls are
        // unaffected (only the SSE stream opts in per-request).
        install(HttpTimeout)
        defaultRequest {
            url(baseUrl)
            contentType(ContentType.Application.Json)
            tokens.currentAccessToken()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        }
    }
}

/**
 * Supplies the current backend session access token for the Authorization header.
 * Implemented per platform (iOS: reads the Keychain token cache that login wrote).
 * Returns null when signed out.
 */
interface SessionTokenProvider {
    fun currentAccessToken(): String?
}
