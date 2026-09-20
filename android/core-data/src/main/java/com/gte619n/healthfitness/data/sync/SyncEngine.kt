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
    // replay=1 so a subscriber that attaches just after a pull still observes the
    // most recent "updated elsewhere" note (the ViewModel latch + the
    // deterministic test both rely on the latest signal being retained rather than
    // lost to a subscription-timing race).
    private val _updatedElsewhere = MutableSharedFlow<UpdatedElsewhere>(replay = 1, extraBufferCapacity = 32)
    val updatedElsewhere: SharedFlow<UpdatedElsewhere> = _updatedElsewhere

    data class UpdatedElsewhere(val table: String, val id: String)

    /**
     * OBS-005: emits every pulled change the client could not route to a local
     * table (`ApplyOutcome.SKIPPED`) — the exact silent-data-loss class behind the
     * past slash-collection bug. A subscriber (crash-reporter non-fatal, debug
     * screen) can surface it so an unknown/renamed backend collection is visible
     * on day one instead of discovered weeks later. replay=1 so a late subscriber
     * still sees the most recent drop.
     */
    private val _skippedChanges = MutableSharedFlow<SkippedChange>(replay = 1, extraBufferCapacity = 32)
    val skippedChanges: SharedFlow<SkippedChange> = _skippedChanges

    data class SkippedChange(val collection: String, val id: String)

    /** Result of a pull pass, for callers/workers to log/observe. */
    data class PullResult(
        val pages: Int,
        val applied: Int,
        val rejected: Int,
        val discardedLocal: Int,
        val wiped: Boolean,
        val killSwitch: Boolean,
        // OBS-005: count of pulled changes dropped because no local table could be
        // resolved for their collection. >0 means data the server sent never
        // reached the device — a signal to alert on, not silence.
        val skipped: Int = 0,
    )

    /**
     * Pull the delta to completion (`hasMore=false`).
     *
     * @param maxPages cap the number of pages pulled in this pass. The default
     *        [Int.MAX_VALUE] means "drain the whole delta". The cursor is persisted
     *        after each page, so a capped pull simply leaves `hasMore=true` work for
     *        the next (unbounded) pull to finish.
     * @param recentSince ISO-8601 lower bound for heavy time-series (D14, #37).
     *        Passed verbatim as the `recentSince` query param on every page of this
     *        pull. The first-run gate ([FirstSyncGate]) supplies `now - 14d` so the
     *        initial blocking sync is **date-windowed** (last 14 days of heavy
     *        series + full CRUD), then runs a second `recentSince = null` pull that
     *        backfills the older history continuing the SAME cursor (no skip/dup).
     */
    @Suppress("ReturnCount")
    suspend fun pull(maxPages: Int = Int.MAX_VALUE, recentSince: String? = null): PullResult = withContext(io) {
        // If the kill-switch is already latched, do not pull (DB is not the
        // source of truth until a future delta clears it).
        var cursor = ensureState().cursor
        var pages = 0
        var applied = 0
        var rejected = 0
        var discarded = 0
        var skipped = 0
        var wiped = false

        while (true) {
            val resp = api.delta(
                since = cursor,
                schemaVersion = SYNC_SCHEMA_VERSION,
                recentSince = recentSince,
            )

            // D13 kill-switch: persist + stop. Leaves the cursor as-is so a later
            // clear resumes where we were.
            if (resp.killSwitch) {
                flags.setKillSwitch(true)
                return@withContext PullResult(
                    pages, applied, rejected, discarded, wiped, killSwitch = true, skipped = skipped,
                )
            } else {
                // A delta that does NOT assert the kill-switch clears any prior latch.
                flags.setKillSwitch(false)
            }

            // D13 schemaVersion mismatch: wipe Room + restart from empty cursor.
            if (resp.schemaVersion != SYNC_SCHEMA_VERSION) {
                dbWiper.wipeMirrors()
                syncStateDao.upsert(
                    SyncStateEntity(cursor = null, schemaVersion = MIRROR_SCHEMA_VERSION, lastFullSyncAt = null),
                )
                cursor = null
                wiped = true
                // Re-loop from scratch against the (now-empty) cursor.
                pages = 0; applied = 0; rejected = 0; discarded = 0; skipped = 0
                continue
            }

            pages++
            // Apply the whole page in one transaction so Room observers (and the
            // charts they back) see a single emission per page rather than one per
            // changed row streaming in.
            mirror.runInTransaction {
                for (change in resp.changes) {
                    when (applyChange(change)) {
                        ApplyOutcome.APPLIED -> applied++
                        ApplyOutcome.REJECTED -> rejected++
                        ApplyOutcome.DISCARDED_LOCAL -> { applied++; discarded++ }
                        // OBS-005: a pulled change we couldn't route to any local
                        // table. Previously dropped silently — the slash-collection
                        // data-loss class. Now count it, WARN with the offending
                        // collection/id, and emit it so an operator surface can
                        // alert. Routing itself is unchanged.
                        ApplyOutcome.SKIPPED -> {
                            skipped++
                            // Guard so android.util.Log (unmocked on the plain-JVM
                            // unit-test classpath) never breaks the pull loop.
                            runCatching {
                                android.util.Log.w(
                                    LOG_TAG,
                                    "Dropped unroutable sync change: collection=${change.collection} id=${change.id}",
                                )
                            }
                            _skippedChanges.tryEmit(SkippedChange(change.collection, change.id))
                        }
                    }
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
                ?: SyncStateEntity(cursor = cursor, schemaVersion = MIRROR_SCHEMA_VERSION, lastFullSyncAt = null))
                .copy(cursor = cursor, lastFullSyncAt = System.currentTimeMillis()),
        )

        PullResult(pages, applied, rejected, discarded, wiped, killSwitch = false, skipped = skipped)
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
            val payloadJson = payloadWithId(table, change.id, change.doc)
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

    /**
     * Serialize a pulled change's `doc`, injecting the document id under the
     * field name the table's DTO expects (a Firestore doc has no id field of its
     * own). Without this a pulled row decodes with a missing/blank id — required-id
     * DTOs (gyms, programs, medications) fail to decode and vanish; default-id ones
     * (body composition) collide on a blank LazyColumn key and crash the screen.
     */
    private fun payloadWithId(table: String, id: String, doc: Map<String, Any?>?): String {
        if (doc == null) return "{}"
        val idField = CollectionRegistry.idFieldFor(table)
        val withId = if (idField != null && doc[idField] == null) doc + (idField to id) else doc
        return mapAdapter.toJson(withId)
    }

    private suspend fun ensureState(): SyncStateEntity {
        syncStateDao.get()?.let { existing ->
            // One-time full re-sync when the client's mirror generation advances —
            // i.e. the app added a new synced collection ([MIRROR_SCHEMA_VERSION]).
            // Reset the cursor to null so the NEXT pull is a full scan that
            // backfills the new collection; a plain delta would leave rows created
            // before this install's cursor orphaned — the class of bug where a
            // web-created ad-hoc workout never reached the phone (the pre-support
            // build SKIPPED the change and advanced the cursor past it, so the
            // supporting build never re-requested it). Distinct from the D13
            // SERVER-driven wipe (resp.schemaVersion vs [SYNC_SCHEMA_VERSION]):
            // this is client-local, compares the STORED generation to the current
            // one, updates it once, and so can never wipe-loop. No mirror wipe is
            // needed — a full re-pull is LWW-idempotent over the rows already held.
            if (existing.schemaVersion != MIRROR_SCHEMA_VERSION) {
                val migrated = existing.copy(cursor = null, schemaVersion = MIRROR_SCHEMA_VERSION)
                syncStateDao.upsert(migrated)
                return migrated
            }
            return existing
        }
        val fresh = SyncStateEntity(cursor = null, schemaVersion = MIRROR_SCHEMA_VERSION, lastFullSyncAt = null)
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
        /**
         * Wire sync-protocol version (D13), sent on every request and compared to
         * the server's advertised version. SERVER-driven: a mismatch means the
         * server changed its protocol and the client wipes + full-resyncs. Must
         * track the backend's value — never bump this client-only or every pull
         * mismatches the (unchanged) server value and wipe-loops.
         */
        const val SYNC_SCHEMA_VERSION = 1

        /**
         * Client-local mirror generation: the set of synced collections this build
         * mirrors. Persisted in [SyncStateEntity.schemaVersion]; when it advances
         * (a new synced collection was added), [ensureState] resets the cursor once
         * so a full scan backfills the new collection on existing installs.
         *
         * BUMP THIS whenever a collection is added to the sync mirror
         * (CollectionRegistry / MirrorTables) — otherwise rows created before an
         * install's cursor stay orphaned and never appear (see [ensureState]).
         * gen 2 = added adhocWorkouts + adhocWorkouts/sessions (IMPL-ADHOC-01).
         */
        const val MIRROR_SCHEMA_VERSION = 2

        /** OBS-005: logcat tag for dropped-unroutable-change warnings. */
        private const val LOG_TAG = "HFSync"

        /** Parse an ISO-8601 instant to epoch millis, tolerant of a numeric string. */
        fun parseMillis(iso: String): Long =
            iso.toLongOrNull() ?: Instant.parse(iso).toEpochMilli()
    }
}
