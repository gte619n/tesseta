package com.gte619n.healthfitness.data.withings

import com.gte619n.healthfitness.domain.withings.WithingsStatus
import java.time.Instant
import javax.inject.Inject

// Concrete @Inject repository (single implementation — no domain interface),
// mirroring GoogleHealthRepository.
class WithingsRepository @Inject constructor(
    private val service: WithingsService,
) {

    suspend fun status(): Result<WithingsStatus> = runCatching {
        service.status().toDomain()
    }

    suspend fun check(): Result<WithingsStatus> = runCatching {
        service.check().toDomain()
    }

    suspend fun connect(code: String, redirectUri: String): Result<Unit> =
        runCatching { service.connect(WithingsConnectBody(code, redirectUri)) }

    suspend fun disconnect(): Result<Unit> =
        runCatching { service.disconnect() }

    private fun WithingsStatusDto.toDomain() = WithingsStatus(
        connected = connected,
        connectedAtEpochSeconds = connectedAt
            ?.takeIf { it.isNotBlank() }
            ?.let { Instant.parse(it).epochSecond },
        needsReconnect = needsReconnect,
        brokenReason = brokenReason,
    )
}
