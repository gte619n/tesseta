package com.gte619n.healthfitness.domain.profile

interface ProfileRepository {
    suspend fun get(): Result<Profile>
    suspend fun updateHeightCm(heightCm: Int?): Result<Profile>
}
