package com.gte619n.healthfitness.data.profile

import com.gte619n.healthfitness.domain.profile.Profile
import com.gte619n.healthfitness.domain.profile.ProfileRepository
import javax.inject.Inject

class ProfileRepositoryImpl @Inject constructor(
    private val service: ProfileService,
) : ProfileRepository {

    override suspend fun get(): Result<Profile> =
        runCatching { service.get().toDomain() }

    override suspend fun updateHeightCm(heightCm: Int?): Result<Profile> =
        runCatching { service.patch(PatchProfileBody(heightCm)).toDomain() }
}

private fun ProfileDto.toDomain() = Profile(
    userId = userId,
    email = email,
    displayName = displayName,
    heightCm = heightCm,
    photoUrl = photoUrl,
)
