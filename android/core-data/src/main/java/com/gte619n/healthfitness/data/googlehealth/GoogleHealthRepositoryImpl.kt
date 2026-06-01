package com.gte619n.healthfitness.data.googlehealth

import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthRepository
import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthStatus
import java.time.Instant
import javax.inject.Inject

class GoogleHealthRepositoryImpl @Inject constructor(
    private val service: GoogleHealthService,
) : GoogleHealthRepository {

    override suspend fun status(): Result<GoogleHealthStatus> = runCatching {
        val dto = service.status()
        GoogleHealthStatus(
            connected = dto.connected,
            connectedAtEpochSeconds = dto.connectedAt
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it).epochSecond },
        )
    }

    override suspend fun connectWithServerAuthCode(serverAuthCode: String): Result<Unit> =
        runCatching { service.connect(ConnectBody(serverAuthCode)) }

    override suspend fun disconnect(): Result<Unit> =
        runCatching { service.disconnect() }
}
