package com.gte619n.healthfitness.data.profile

import com.gte619n.healthfitness.data.di.IoDispatcher
import com.gte619n.healthfitness.domain.profile.Profile
import com.gte619n.healthfitness.domain.profile.ProfileRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
internal class ProfileRepositoryImpl @Inject constructor(
    private val service: ProfileService,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ProfileRepository {

    override suspend fun get(): Result<Profile> = withContext(io) {
        runCatching { service.get().toDomain() }
    }

    override suspend fun updateHeightCm(heightCm: Int?): Result<Profile> = withContext(io) {
        runCatching { service.patch(PatchProfileBody(heightCm)).toDomain() }
    }
}

private fun ProfileDto.toDomain() = Profile(
    userId = userId,
    email = email,
    displayName = displayName,
    heightCm = heightCm,
)
