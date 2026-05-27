package com.gte619n.healthfitness.domain.profile

/**
 * Profile-read / partial-update contract. Implementations live in
 * `core-data`. ViewModels depend on this interface; tests use
 * in-memory fakes.
 *
 * The patch surface is intentionally minimal — only `heightCm` is
 * editable today. As additional fields graduate from the JWT-baked
 * Google identity payload into user-editable profile fields, prefer
 * adding a single `partial: ProfileUpdate` record over expanding the
 * parameter list.
 */
interface ProfileRepository {
    suspend fun get(): Result<Profile>
    suspend fun updateHeightCm(heightCm: Int?): Result<Profile>
}
