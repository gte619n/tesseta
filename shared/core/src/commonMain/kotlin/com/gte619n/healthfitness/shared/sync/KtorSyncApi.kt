package com.gte619n.healthfitness.shared.sync

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ResponseException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent

/**
 * IMPL-IOS-01 Phase 1C — the sync HTTP surface over the shared authenticated Ktor
 * client ([com.gte619n.healthfitness.shared.net.ApiClient], expectSuccess = true).
 *
 *  - [pull] is the unified delta read `GET /api/me/sync` (lets failures throw so the
 *    engine catches + retries the whole pull).
 *  - [push] replays one outbox op to its real controller path ([OutboxEndpointRegistry]),
 *    translating HTTP status into a [PushResult]: a 404-on-DELETE is success (the row
 *    was already gone), 401/408/425/429 + 5xx are retryable, other 4xx are terminal.
 *
 * [deviceId] is sent as `X-HF-Origin-Device` so the backend can suppress echoing a
 * device's own writes back to it.
 */
class KtorSyncApi(
    private val client: HttpClient,
    private val deviceId: String,
) : SyncApi {

    override suspend fun pull(since: String?, limit: Int, schemaVersion: Int, recentSince: String?): SyncResponse =
        client.get("api/me/sync") {
            parameter("limit", limit)
            parameter("schemaVersion", schemaVersion)
            since?.let { parameter("since", it) }
            recentSince?.let { parameter("recentSince", it) }
        }.body()

    override suspend fun push(op: OutboxOp): PushResult {
        val resolved = OutboxEndpointRegistry.resolve(op.collection, op.operation, op.entityId)
        return try {
            client.request(resolved.path) {
                method = HttpMethod.parse(resolved.method)
                header("Idempotency-Key", OutboxEndpointRegistry.idempotencyKey(op.collection, op.entityId, op.idempotencyKey))
                header("X-HF-Origin-Device", deviceId)
                if (op.operation != OutboxOp.Operation.DELETE) {
                    op.docJson?.let { setBody(TextContent(it, ContentType.Application.Json)) }
                }
            }
            PushResult(ok = true, serverLastUpdate = null, retryable = false)
        } catch (e: ResponseException) {
            val code = e.response.status.value
            when {
                // A delete of an already-absent row is success (web-doses-outbox-drain-race).
                op.operation == OutboxOp.Operation.DELETE && code == 404 ->
                    PushResult(ok = true, serverLastUpdate = null, retryable = false)
                code in RETRYABLE_CLIENT_CODES || code >= 500 ->
                    PushResult(ok = false, serverLastUpdate = null, retryable = true, error = "HTTP $code")
                else ->
                    PushResult(ok = false, serverLastUpdate = null, retryable = false, error = "HTTP $code")
            }
        } catch (e: Exception) {
            // Network/transport failure — always retryable.
            PushResult(ok = false, serverLastUpdate = null, retryable = true, error = e.message)
        }
    }

    private companion object {
        // 401 tolerates the refresh-token reuse-grace window; 408/425/429 are transient.
        val RETRYABLE_CLIENT_CODES = setOf(401, 408, 425, 429)
    }
}
