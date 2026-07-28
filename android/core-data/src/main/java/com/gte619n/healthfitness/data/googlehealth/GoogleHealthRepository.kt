package com.gte619n.healthfitness.data.googlehealth

import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthStatus
import java.time.Instant
import javax.inject.Inject

// Concrete @Inject repository (single implementation — no domain interface).
class GoogleHealthRepository @Inject constructor(
    private val service: GoogleHealthService,
) {

    suspend fun status(): Result<GoogleHealthStatus> = runCatching {
        service.status().toDomain()
    }

    // Server-side active probe: forces a token exchange and returns the fresh
    // status, so a silently-dead connection surfaces immediately.
    suspend fun check(): Result<GoogleHealthStatus> = runCatching {
        service.check().toDomain()
    }

    private fun GoogleHealthStatusDto.toDomain() = GoogleHealthStatus(
        connected = connected,
        connectedAtEpochSeconds = connectedAt
            ?.takeIf { it.isNotBlank() }
            ?.let { Instant.parse(it).epochSecond },
        needsReconnect = needsReconnect,
        brokenReason = brokenReason,
    )

    suspend fun connectWithServerAuthCode(serverAuthCode: String): Result<Unit> =
        runCatching { service.connect(ConnectBody(serverAuthCode)) }

    suspend fun disconnect(): Result<Unit> =
        runCatching { service.disconnect() }
}
