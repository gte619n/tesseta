package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.profile.Profile
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * IMPL-IOS-01 Phase 1C — online-first [ProfileRepository] over the existing
 * backend endpoints (GET /api/me, partial PATCH /api/me), mirroring Android's
 * `ProfileService` contract. No offline mirror yet (that's the sync-engine
 * layer); `cached()` returns null so the ViewModel fetches from the network.
 *
 * Each PATCH sends exactly the one field being changed as an explicit JSON
 * object, so an intentional null (e.g. clear height) is preserved while the
 * other fields are omitted (= left unchanged by the backend).
 */
class HttpProfileRepository(private val client: HttpClient) : ProfileRepository {

    override suspend fun cached(): Profile? = null

    override suspend fun get(): Result<Profile> = runCatching {
        client.get("api/me").body<Profile>()
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
}
