package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.dao.SyncStateDao
import com.gte619n.healthfitness.data.db.entity.SyncRowState
import com.gte619n.healthfitness.data.db.entity.SyncRowStatus
import com.gte619n.healthfitness.data.db.entity.SyncStateEntity
import com.gte619n.healthfitness.data.di.IoDispatcher
import com.squareup.moshi.Moshi
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 4) — the delta-pull half of the sync engine (D6/D3/D13).
 *
 * [pull] loops `GET /api/me/sync?since=<cursor>` until `hasMore=false`, applying
 * each change to the local mirror via the [ConflictResolver] (LWW on the server
 * `lastUpdate`), persisting `nextCursor` after each page. It handles the two
 * protocol-control cases from D13:
 *  - **schemaVersion mismatch** ⇒ wipe Room and restart from an empty cursor.
 *  - **killSwitch=true** ⇒ persist the disable flag (Phase 5/6 drop to live
 *    network) and stop pulling.
 *
 * It is written against narrow seams ([SyncApi], [MirrorOps], [SyncStateDao],
 * [DbWiper], [SyncFlags]) so it can be unit-tested on the pure JVM: the
 * MockWebServer test drives a real Retrofit [SyncApi] but supplies an in-memory
 * fake [MirrorOps] / [SyncStateDao] / [DbWiper], so there is **no Room, no
 * SQLCipher, and no device/Robolectric** in the test path.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val api: SyncApi,
    private val mirror: MirrorOps,
    private val syncStateDao: SyncStateDao,
    private val dbWiper: DbWiper,
    private val flags: KillSwitchSink,
    private val moshi: Moshi,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /**
     * "Updated elsewhere" signal (D11): emits the table+id of any local dirty
     * edit discarded by LWW so Phase 6 UX can surface the lightweight note.
     */
    private val _updatedElsewhere = MutableSharedFlow<UpdatedElsewhere>(extraBufferCapacity = 32)
    val updatedElsewhere: SharedFlow<UpdatedElsewhere> = _updatedElsewhere

    data class UpdatedElsewhere(val table: String, val id: String)

    /** Result of a pull pass, for callers/workers to log/observe. */
    data class PullResult(
        val pages: Int,
        val applied: Int,
        val rejected: Int,
        val discardedLocal: Int,
        val wiped: Boolean,
        val killSwitch: Boolean,
    )

    /**
     * Pull the delta to completion (`hasMore=false`).
     *
     * @param maxPages cap the number of pages pulled in this pass. The default
     *        [Int.MAX_VALUE] means "drain the whole delta". The first-run gate
     *        (D14) passes a small cap so it can release the UI after a brief,
     *        bounded initial sync, then a background pass backfills the rest. The
     *        cursor is persisted after each page, so a capped pull simply leaves
     *        `hasMore=true` work for the next (unbounded) pull to finish.
     */
    @Suppress("ReturnCount")
    suspend fun pull(maxPages: Int = Int.MAX_VALUE): PullResult = withContext(io) {
        // If the kill-switch is already latched, do not pull (DB is not the
        // source of truth until a future delta clears it).
        var cursor = ensureState().cursor
        var pages = 0
        var applied = 0
        var rejected = 0
        var discarded = 0
        var wiped = false

        while (true) {
            val resp = api.delta(since = cursor, schemaVersion = SYNC_SCHEMA_VERSION)

            // D13 kill-switch: persist + stop. Leaves the cursor as-is so a later
            // clear resumes where we were.
            if (resp.killSwitch) {
                flags.setKillSwitch(true)
                return@withContext PullResult(pages, applied, rejected, discarded, wiped, killSwitch = true)
            } else {
                // A delta that does NOT assert the kill-switch clears any prior latch.
                flags.setKillSwitch(false)
            }

            // D13 schemaVersion mismatch: wipe Room + restart from empty cursor.
            if (resp.schemaVersion != SYNC_SCHEMA_VERSION) {
                dbWiper.wipeMirrors()
                syncStateDao.upsert(
                    SyncStateEntity(cursor = null, schemaVersion = SYNC_SCHEMA_VERSION, lastFullSyncAt = null),
                )
                cursor = null
                wiped = true
                // Re-loop from scratch against the (now-empty) cursor.
                pages = 0; applied = 0; rejected = 0; discarded = 0
                continue
            }

            pages++
            for (change in resp.changes) {
                when (applyChange(change)) {
                    ApplyOutcome.APPLIED -> applied++
                    ApplyOutcome.REJECTED -> rejected++
                    ApplyOutcome.DISCARDED_LOCAL -> { applied++; discarded++ }
                    ApplyOutcome.SKIPPED -> Unit
                }
            }

            // Persist the cursor after each page so a crash resumes mid-stream.
            cursor = resp.nextCursor
            syncStateDao.updateCursor(cursor)

            if (!resp.hasMore) break
            // D14 first-run gate: stop after the bounded window; the remaining
            // pages backfill on the next unbounded pull (periodic/foreground).
            if (pages >= maxPages) break
        }

        syncStateDao.upsert(
            (syncStateDao.get()
                ?: SyncStateEntity(cursor = cursor, schemaVersion = SYNC_SCHEMA_VERSION, lastFullSyncAt = null))
                .copy(cursor = cursor, lastFullSyncAt = System.currentTimeMillis()),
        )

        PullResult(pages, applied, rejected, discarded, wiped, killSwitch = false)
    }

    private enum class ApplyOutcome { APPLIED, REJECTED, DISCARDED_LOCAL, SKIPPED }

    private suspend fun applyChange(change: SyncChange): ApplyOutcome {
        val table = CollectionRegistry.tableFor(change.collection) ?: return ApplyOutcome.SKIPPED
        val incomingMillis = parseMillis(change.lastUpdate)

        val local = mirror.getRow(table, change.id)?.let {
            ConflictResolver.LocalRow(lastUpdate = it.lastUpdate, dirty = it.dirty)
        }

        return when (ConflictResolver.resolve(incomingMillis, local)) {
            ConflictResolver.Decision.Reject -> ApplyOutcome.REJECTED
            ConflictResolver.Decision.Apply -> {
                writeChange(table, change, incomingMillis)
                ApplyOutcome.APPLIED
            }
            ConflictResolver.Decision.ApplyDiscardingLocal -> {
                writeChange(table, change, incomingMillis)
                _updatedElsewhere.tryEmit(UpdatedElsewhere(table, change.id))
                ApplyOutcome.DISCARDED_LOCAL
            }
        }
    }

    private suspend fun writeChange(table: String, change: SyncChange, incomingMillis: Long) {
        if (change.status == SyncRowStatus.ARCHIVED.name) {
            // Tombstone: keep the row but mark archived (so a late LWW compare
            // still has a lastUpdate to beat); ARCHIVED rows are filtered out of
            // the UI Flows.
            mirror.markArchived(table, change.id, incomingMillis)
        } else {
            val payloadJson = change.doc?.let { mapAdapter.toJson(it) } ?: "{}"
            mirror.upsert(
                table,
                MirrorRowData(
                    id = change.id,
                    payloadJson = payloadJson,
                    lastUpdate = incomingMillis,
                    status = SyncRowStatus.ACTIVE.name,
                    dirty = false,
                    syncState = SyncRowState.SYNCED.name,
                ),
            )
        }
    }

    private suspend fun ensureState(): SyncStateEntity {
        syncStateDao.get()?.let { return it }
        val fresh = SyncStateEntity(cursor = null, schemaVersion = SYNC_SCHEMA_VERSION, lastFullSyncAt = null)
        syncStateDao.upsert(fresh)
        return fresh
    }

    private val mapAdapter by lazy {
        moshi.adapter<Map<String, Any?>>(
            com.squareup.moshi.Types.newParameterizedType(
                Map::class.java, String::class.java, Any::class.java,
            ),
        )
    }

    companion object {
        /** Client sync-protocol version (D13). Bump ⇒ wipe + full resync. */
        const val SYNC_SCHEMA_VERSION = 1

        /** Parse an ISO-8601 instant to epoch millis, tolerant of a numeric string. */
        fun parseMillis(iso: String): Long =
            iso.toLongOrNull() ?: Instant.parse(iso).toEpochMilli()
    }
}
