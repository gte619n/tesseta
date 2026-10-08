package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.profile.Profile
import com.gte619n.healthfitness.shared.sync.MirrorTables
import com.gte619n.healthfitness.shared.sync.SqlDelightMirrorStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * IMPL-IOS-01 (#2) — mirror-backed [ProfileRepository]. [cached] serves the synced
 * userProfile doc from the on-device mirror (instant + offline); [get] serves the
 * mirror first and only hits the network when the mirror is empty (pre-first-sync) —
 * matching the interface contract. Writes stay online (partial PATCH /api/me); the
 * next delta pull re-mirrors the authoritative profile.
 */
class MirrorProfileRepository(
    private val mirror: SqlDelightMirrorStore,
    private val client: HttpClient,
    private val json: Json = LENIENT,
) : ProfileRepository {

    override suspend fun cached(): Profile? =
        mirror.firstActiveRecord(MirrorTables.USER_PROFILE)
            ?.let { runCatching { json.decodeFromString(Profile.serializer(), it.payloadJson) }.getOrNull() }

    override suspend fun get(): Result<Profile> = runCatching {
        cached() ?: client.get("api/me").body<Profile>()
    }

    override suspend fun updateHeightCm(heightCm: Int?): Result<Profile> =
        patch(buildJsonObject { put("heightCm", heightCm) })

    override suspend fun updateBiologicalSex(biologicalSex: String?): Result<Profile> =
        patch(buildJsonObject { put("biologicalSex", biologicalSex) })

    override suspend fun updateDateOfBirth(dateOfBirth: String?): Result<Profile> =
        patch(buildJsonObject { put("dateOfBirth", dateOfBirth) })

    private suspend fun patch(body: JsonObject): Result<Profile> = runCatching {
        client.patch("api/me") { setBody(body) }.body<Profile>()
    }

    private companion object {
        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    }
}
