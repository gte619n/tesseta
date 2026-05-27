package com.gte619n.healthfitness.data.googlehealth

import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthRepository
import com.gte619n.healthfitness.domain.googlehealth.GoogleHealthStatus
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
internal class GoogleHealthRepositoryImpl @Inject constructor(
    private val service: GoogleHealthService,
    @IoDispatcher private val io: CoroutineDispatcher,
) : GoogleHealthRepository {

    override suspend fun status(): Result<GoogleHealthStatus> = withContext(io) {
        runCatching {
            val dto = service.status()
            GoogleHealthStatus(
                connected = dto.connected,
                connectedAtEpochSeconds = dto.connectedAt?.let { iso ->
                    runCatching { Instant.parse(iso).epochSecond }.getOrNull()
                },
            )
        }
    }

    override suspend fun connectWithServerAuthCode(serverAuthCode: String): Result<Unit> =
        withContext(io) {
            runCatching { service.connect(ConnectBody(serverAuthCode)) }
        }

    override suspend fun disconnect(): Result<Unit> = withContext(io) {
        runCatching { service.disconnect() }
    }
}
