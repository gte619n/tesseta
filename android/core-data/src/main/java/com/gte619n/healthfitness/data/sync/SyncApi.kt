package com.gte619n.healthfitness.data.sync

import com.squareup.moshi.JsonClass
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PUT
import retrofit2.http.Query

/**
 * IMPL-AND-20 (Phase 4) — Retrofit binding for the unified delta-read API (D6)
 * and the FCM device-token registry (D18).
 *
 * `GET /api/me/sync` returns all changed in-scope docs (including tombstones)
 * since an opaque cursor, paginated. The `Authorization: Bearer` header is added
 * by the existing `AuthInterceptor`.
 */
interface SyncApi {

    /**
     * @param since opaque server cursor; omit (null) for the initial full sync.
     * @param limit page size (server caps it).
     * @param schemaVersion client's sync-protocol version; a server mismatch
     *        signals the client to wipe Room and full-resync (D13).
     */
    @GET("api/me/sync")
    suspend fun delta(
        @Query("since") since: String?,
        @Query("limit") limit: Int = 500,
        @Query("schemaVersion") schemaVersion: Int,
    ): SyncDeltaResponse

    // FCM device-token registry (D18). Defined here so the registry contract
    // lives next to the sync contract, but the *client wiring* (register on
    // sign-in / delete on sign-out, FirebaseMessagingService) is Phase 6.
    @PUT("api/me/devices/fcm")
    suspend fun registerFcmToken(@Body body: FcmTokenRequest)

    @HTTP(method = "DELETE", path = "api/me/devices/fcm", hasBody = true)
    suspend fun deleteFcmToken(@Body body: FcmDeviceRequest)
}

@JsonClass(generateAdapter = false)
data class SyncDeltaResponse(
    val schemaVersion: Int,
    val serverTime: String?,
    val changes: List<SyncChange> = emptyList(),
    val nextCursor: String?,
    val hasMore: Boolean = false,
    val killSwitch: Boolean = false,
)

@JsonClass(generateAdapter = false)
data class SyncChange(
    /** Backend collection string; mapped to a Room table via [CollectionRegistry]. */
    val collection: String,
    val id: String,
    /** ACTIVE | ARCHIVED (tombstone). */
    val status: String,
    /** ISO-8601 server timestamp (parsed to epoch millis on apply). */
    val lastUpdate: String,
    /** Full domain payload for ACTIVE; null for ARCHIVED tombstones. */
    val doc: Map<String, Any?>? = null,
)

@JsonClass(generateAdapter = false)
data class FcmTokenRequest(val token: String, val deviceId: String)

@JsonClass(generateAdapter = false)
data class FcmDeviceRequest(val deviceId: String)
