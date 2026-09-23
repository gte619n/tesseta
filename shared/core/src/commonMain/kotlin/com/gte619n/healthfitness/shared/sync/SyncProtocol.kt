package com.gte619n.healthfitness.shared.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 1C — the wire contract of the unified delta read
 * (`GET /api/me/sync`), ported to `commonMain` from the Android sync engine so
 * one set of serializers covers both platforms. Matches the backend
 * `SyncResponse` record (backend .../api/sync/SyncResponse.java) exactly; the
 * contract fixtures under `contracts/fixtures/` are the round-trip proof.
 */
@Serializable
data class SyncResponse(
    val schemaVersion: Int,
    val serverTime: String,
    val changes: List<ChangeDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val killSwitch: Boolean = false,
)

/**
 * One changed document. [status] is the sync lifecycle status
 * (`ACTIVE`/`ARCHIVED`); `ARCHIVED` carries `doc: null` as a tombstone.
 * [lastUpdate] is the server-clock cursor + LWW ordering key.
 */
@Serializable
data class ChangeDto(
    val collection: String,
    val id: String,
    val status: String,
    val lastUpdate: String,
    // Kept as an opaque JSON element: the engine dispatches on [collection] to
    // the right table and stores the raw payload (mirror rows are payloadJson),
    // exactly as the Android engine does.
    val doc: kotlinx.serialization.json.JsonElement? = null,
)

enum class SyncStatus { ACTIVE, ARCHIVED }

/**
 * The per-row LWW result the [MergeConflictResolver] returns. Ported from the
 * Android resolver: server `lastUpdate` strictly greater than local → apply
 * server; else keep local (a dirty local edit that ties or leads is preserved).
 */
enum class MergeOutcome { APPLY_SERVER, KEEP_LOCAL }

/**
 * Pure, platform-agnostic LWW policy — the single source of truth both clients
 * share. [flaky-syncenginepulltest-lww] documented the Android edge cases this
 * must preserve (dirty local edit that loses/ties LWW).
 */
object MergeConflictResolver {
    fun resolve(serverLastUpdate: String, localLastUpdate: String?, localDirty: Boolean): MergeOutcome {
        if (localLastUpdate == null) return MergeOutcome.APPLY_SERVER
        // ISO-8601 UTC instants compare correctly lexicographically.
        return when {
            serverLastUpdate > localLastUpdate -> MergeOutcome.APPLY_SERVER
            serverLastUpdate < localLastUpdate -> MergeOutcome.KEEP_LOCAL
            // Tie: a dirty local edit wins (it has an un-pushed change), a clean
            // local row yields to the server (idempotent re-apply).
            else -> if (localDirty) MergeOutcome.KEEP_LOCAL else MergeOutcome.APPLY_SERVER
        }
    }
}
