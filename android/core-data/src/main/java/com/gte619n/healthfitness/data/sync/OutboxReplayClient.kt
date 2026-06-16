package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.MirrorTables
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import com.gte619n.healthfitness.data.net.BackendBaseUrl
import com.squareup.moshi.Moshi
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 4) — replays one collapsed outbox mutation to the backend.
 *
 * The spec routes writes back through the **existing mutating endpoints** with
 * `Idempotency-Key` + `X-HF-Origin-Device` headers and a client-minted `id` in
 * the body. The per-domain endpoint map is finalized in Phase 5 (read-path
 * refactor, where each repository owns its outbox enqueue/endpoint); for Phase 4
 * this generic OkHttp client performs a conventional REST replay so the drain
 * loop, header attachment, and success/failure plumbing are real and testable.
 *
 * It is an interface so the OutboxRepository drain logic is unit-tested with a
 * fake (no network) and the MockWebServer test asserts the real header/body
 * wire shape.
 */
interface OutboxReplayClient {
    /**
     * @return the server-confirmed `lastUpdate` (epoch millis) for the row on
     *         success. Throws [OutboxReplayHttpException] on a non-2xx response
     *         (and anything else on transport failure) so the drain loop can
     *         back off — or park the row when the rejection is terminal.
     */
    suspend fun replay(
        table: String,
        op: OutboxOp,
        entityId: String,
        payloadJson: String?,
        mutationId: String,
        originDeviceId: String,
    ): Long
}

/**
 * A non-2xx replay response. [isTerminal] marks deterministic client rejections
 * — replaying the identical payload can only produce the identical 4xx (e.g.
 * the IMPL-17 completion upsert 400/404 after a concurrent program rewrite
 * deleted the scheduled session or its block ids), so the drain loop parks the
 * row instead of retrying forever.
 */
class OutboxReplayHttpException(
    val code: Int,
    message: String,
    /** The server's error body (best-effort), so the failure reason can be
     *  surfaced to the user instead of swallowed. Null/blank when absent. */
    val serverMessage: String? = null,
) : RuntimeException(message) {
    val isTerminal: Boolean
        get() = code in 400..499 && code !in RETRYABLE_CLIENT_CODES

    companion object {
        // 401 can recover after a token refresh / re-login, 408/425 are
        // transport timing, and 429 clears once rate limiting lifts.
        private val RETRYABLE_CLIENT_CODES = setOf(401, 408, 425, 429)
    }
}

/**
 * Generic REST replay: maps op→HTTP method against `"$baseUrl/api/me/<table>"`
 * (+`/<id>` for UPDATE/DELETE). Attaches the idempotency + origin-device headers
 * and the bearer token (the shared [OkHttpClient] already carries the
 * `AuthInterceptor`). Phase 5 replaces/overrides per-domain paths as repos adopt
 * the outbox.
 */
@Singleton
class RestOutboxReplayClient @Inject constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    @BackendBaseUrl private val baseUrl: String,
) : OutboxReplayClient {

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()
    private val mapAdapter = moshi.adapter<Map<String, Any?>>(
        com.squareup.moshi.Types.newParameterizedType(
            Map::class.java, String::class.java, Any::class.java,
        ),
    )

    override suspend fun replay(
        table: String,
        op: OutboxOp,
        entityId: String,
        payloadJson: String?,
        mutationId: String,
        originDeviceId: String,
    ): Long {
        val root = baseUrl.trimEnd('/').toHttpUrl()
        // The per-domain endpoint map (Phase 5) owns method + URL so each table
        // replays to its real backend controller path; unmapped tables fall back
        // to the Phase-4 generic `api/me/<table>` shape.
        val resolved = OutboxEndpointRegistry.resolve(root, table, op, entityId)
        val url = resolved.url
        val method = resolved.method

        val body = when (op) {
            OutboxOp.DELETE -> null
            else -> wireBody(table, payloadJson).toRequestBody(jsonMedia)
        }

        // Most tables key idempotency by the random per-mutation id; adherence
        // (#24) derives a deterministic `(med,date)` key so a re-queued dose log is
        // a server no-op (matching the backend's med+date idempotency scope).
        val idempotencyKey = OutboxEndpointRegistry.idempotencyKey(table, entityId, mutationId)

        val request = Request.Builder()
            .url(url)
            .method(method, body)
            .header("Idempotency-Key", idempotencyKey)
            .header("X-HF-Origin-Device", originDeviceId)
            .build()

        client.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw OutboxReplayHttpException(
                    code = resp.code,
                    message = "Outbox replay failed: HTTP ${resp.code} for $method $url",
                    serverMessage = serverErrorMessage(text),
                )
            }
            return extractLastUpdate(text)
        }
    }

    /**
     * Pull a human-readable reason out of the error body. Spring's default error
     * shape is `{"message":…}` / `{"detail":…}` / `{"error":…}`; fall back to the
     * raw (trimmed, length-capped) body for plain-text responses.
     */
    private fun serverErrorMessage(text: String): String? {
        if (text.isBlank()) return null
        val fromJson = runCatching {
            val map = mapAdapter.fromJson(text)
            (map?.get("message") ?: map?.get("detail") ?: map?.get("error"))?.toString()
        }.getOrNull()
        return (fromJson?.takeIf { it.isNotBlank() } ?: text).trim().take(300)
    }

    /**
     * The JSON body to replay. Most tables store the bare endpoint DTO as
     * `payloadJson`, which is already the shape the controller expects. Nutrition
     * entries are the exception: the mirror stores a date-wrapped row
     * (`{"date":…, "entry":{…}}`) so the day view can reassemble per date — but
     * the backend's `EntryRequest`/`EntryPatchRequest` expect the entry's fields
     * (meal, foodName, serving…) at the top level. Unwrap to the inner `entry`
     * so e.g. moving an entry between meals actually patches its `meal`.
     */
    private fun wireBody(table: String, payloadJson: String?): String {
        val json = payloadJson ?: "{}"
        if (table != MirrorTables.NUTRITION_ENTRIES) return json
        return runCatching {
            val map = mapAdapter.fromJson(json)
            @Suppress("UNCHECKED_CAST")
            val entry = map?.get("entry") as? Map<String, Any?>
            if (entry != null) mapAdapter.toJson(entry) else json
        }.getOrDefault(json)
    }

    /** Pull the server `lastUpdate` from the write response; fall back to now. */
    private fun extractLastUpdate(text: String): Long {
        if (text.isBlank()) return System.currentTimeMillis()
        return runCatching {
            val map = mapAdapter.fromJson(text)
            when (val lu = map?.get("lastUpdate")) {
                is String -> lu.toLongOrNull() ?: Instant.parse(lu).toEpochMilli()
                is Number -> lu.toLong()
                else -> System.currentTimeMillis()
            }
        }.getOrDefault(System.currentTimeMillis())
    }
}
