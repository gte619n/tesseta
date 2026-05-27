package com.gte619n.healthfitness.domain.googlehealth

/**
 * Backend-side Google Health connection contract. Three operations:
 *
 *  - [status] reads `/api/me/google-health/status`.
 *  - [connectWithServerAuthCode] posts a GIS server auth code to the
 *    backend, which exchanges it for `refresh_token` + `access_token`
 *    against Google's token endpoint and KMS-encrypts the refresh token
 *    for storage. The on-device path does **not** see either token.
 *  - [disconnect] clears the connection record server-side without
 *    revoking the OAuth grant (revocation is a user action via the
 *    Google account UI).
 *
 * Tokens never live on the device. This is a hard rule shared with the
 * web client: only the backend holds the OAuth credentials.
 */
interface GoogleHealthRepository {
    suspend fun status(): Result<GoogleHealthStatus>
    suspend fun connectWithServerAuthCode(serverAuthCode: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
}
