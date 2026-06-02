package com.gte619n.healthfitness.data.profile

import com.gte619n.healthfitness.data.db.dao.UserProfileDao
import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.sync.MirrorRepositorySupport
import com.gte619n.healthfitness.domain.profile.Profile
import com.gte619n.healthfitness.domain.profile.ProfileRepository
import com.squareup.moshi.Moshi
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * IMPL-AND-20 (Phase 5) — Room-backed, offline-first user profile.
 *
 * The profile is a singleton doc (`users/{uid}`). Reads come from the
 * `userProfile` mirror (D8); the network's only job is to fill it via the
 * one-shot fetch inside [get] (and the background SyncEngine pull). A height
 * edit is an optimistic local upsert + outbox enqueue (D7) so it shows instantly
 * and replays to the profile endpoint (see
 * [com.gte619n.healthfitness.data.sync.OutboxEndpointRegistry]).
 *
 * The mirror `payloadJson` is the full [ProfileDto] the screen consumes, so every
 * field round-trips through Room (computed-field gap #7 handled per #15). The
 * interface stays suspend-`Result`-based to keep `ProfileViewModel` unchanged;
 * "Room is the source of truth" is satisfied because the read returns the mirror
 * row, not a live network call (except the kill-switch live fallback, D13).
 */
class ProfileRepositoryImpl @Inject constructor(
    private val service: ProfileService,
    private val dao: UserProfileDao,
    private val support: MirrorRepositorySupport,
    moshi: Moshi,
) : ProfileRepository {

    private val dtoAdapter = moshi.adapter(ProfileDto::class.java)

    override suspend fun get(): Result<Profile> = runCatching {
        if (support.killSwitchOn()) {
            return@runCatching service.get().toDomain()
        }
        // Serve the mirror first; fill from network only when empty (first run / no pull yet).
        mirroredDto()?.let { return@runCatching it.toDomain() }
        val dto = service.get()
        support.refreshInto(
            MirrorTables.USER_PROFILE,
            listOf(
                MirrorRepositorySupport.RefreshRow(
                    id = dto.userId,
                    payloadJson = dtoAdapter.toJson(dto),
                    lastUpdate = System.currentTimeMillis(),
                ),
            ),
        )
        dto.toDomain()
    }

    override suspend fun updateHeightCm(heightCm: Int?): Result<Profile> = runCatching {
        // Carry every field the screen renders (not just the changed height) into
        // the optimistic row so an offline edit shows the full profile instantly.
        val current = mirroredDto() ?: service.get()
        val updated = current.copy(heightCm = heightCm)
        support.updateLocal(
            table = MirrorTables.USER_PROFILE,
            id = updated.userId,
            payloadJson = dtoAdapter.toJson(updated),
            lastUpdate = System.currentTimeMillis(),
        )
        updated.toDomain()
    }

    /** The current mirrored profile DTO, or null when the mirror is empty/undecodable. */
    private suspend fun mirroredDto(): ProfileDto? =
        dao.observeActive().first().firstOrNull()
            ?.let { runCatching { dtoAdapter.fromJson(it.payloadJson) }.getOrNull() }
}

private fun ProfileDto.toDomain() = Profile(
    userId = userId,
    email = email,
    displayName = displayName,
    heightCm = heightCm,
    photoUrl = photoUrl,
)
