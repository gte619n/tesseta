package com.gte619n.healthfitness.domain.googlehealth

interface GoogleHealthRepository {
    suspend fun status(): Result<GoogleHealthStatus>
    suspend fun connectWithServerAuthCode(serverAuthCode: String): Result<Unit>
    suspend fun disconnect(): Result<Unit>
}
