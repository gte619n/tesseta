package com.gte619n.healthfitness.network

/**
 * Surface the network stack uses to read and refresh the current bearer
 * token. Implementations live in `core-data` (bridging to `IdTokenCache`
 * + `GoogleAuthRepository`) so `core-network` doesn't take a dependency on
 * `core-data` — that direction would create a cycle.
 */
interface AuthTokenProvider {
    /** Most recently issued bearer token, or `null` when the user is signed out. */
    suspend fun currentToken(): String?

    /**
     * Performs a silent refresh and returns the new token, or `null` when no
     * refresh path is available (e.g. user signed out, network unavailable).
     */
    suspend fun refresh(): String?
}
