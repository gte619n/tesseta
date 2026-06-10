package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.dao.OutboxDao
import com.gte619n.healthfitness.data.db.entity.OutboxEntity
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import com.gte619n.healthfitness.data.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * IMPL-AND-20 (Phase 4) — the offline write queue + ordered, backed-off drain (D7).
 *
 * [enqueue] mints a `mutationId` (UUID, doubling as the idempotency key), assigns
 * a per-entity-ordered `seq` (global max + 1), stamps the `originDeviceId`, and
 * persists the row. [drain] collapses the queue via [OutboxReducer], then replays
 * the survivors in `seq` order, attaching the idempotency + origin-device headers
 * via [OutboxReplayClient]. On success the mirror row flips to SYNCED+clean and
 * adopts the server `lastUpdate`; on failure the row goes FAILED with exponential
 * backoff on `nextAttemptAt`. A **terminal 4xx** (a deterministic server
 * rejection — see [OutboxReplayHttpException.isTerminal]) is parked instead:
 * the row and its payload are kept (no silent data loss) and stay in the FAILED
 * count, but only the manual retry lever ([rearmFailed], D11) re-attempts it —
 * automatic drains skip it rather than re-sending a doomed payload forever.
 *
 * Drain is invoked after every local write and on connectivity-regained (the
 * WorkManager [OutboxDrainWorker]); it is idempotent and safe to call concurrently
 * with a pull (LWW reconciles).
 */
@Singleton
class OutboxRepository @Inject constructor(
    private val outboxDao: OutboxDao,
    private val mirror: MirrorOps,
    private val replay: OutboxReplayClient,
    private val deviceIdProvider: DeviceIdProvider,
    @IoDispatcher private val io: CoroutineDispatcher,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /** Reactive pending-mutation count for the global sync indicator (D11). */
    fun pendingCount(): Flow<Int> = outboxDao.observePendingCount()

    /**
     * Reactive count of mutations that have failed at least one replay attempt
     * (D11, #39). Drives the distinct global "changes failed — retry" state; a
     * retry re-drains them and a success clears the row.
     */
    fun failedCount(): Flow<Int> = outboxDao.observeFailedCount()

    /**
     * Mutations parked on a terminal 4xx for one table, newest first — the
     * detection seam for feature-level recovery (e.g. restoring a parked
     * workout-session completion into the logger). A manual [rearmFailed]
     * makes a row due again, so it drops off this Flow until it re-parks.
     */
    fun parked(table: String): Flow<List<OutboxEntity>> =
        outboxDao.observeParked(table, PARKED_NEXT_ATTEMPT)

    /** One-shot: the parked mutation(s) for one entity, in `seq` order. */
    suspend fun parkedForEntity(entityId: String): List<OutboxEntity> = withContext(io) {
        outboxDao.listByEntity(entityId).filter { it.nextAttemptAt == PARKED_NEXT_ATTEMPT }
    }

    /**
     * Drop one mutation without replaying it. Only the parked-recovery paths
     * use this: ownership of the rejected payload moves back to a local draft
     * (restore) or the user explicitly gives it up (discard) — never call it
     * for rows the drain still owns.
     */
    suspend fun deleteMutation(mutationId: String): Unit = withContext(io) {
        outboxDao.deleteById(mutationId)
    }

    /**
     * Queue a local mutation. The caller is expected to have already applied the
     * optimistic local upsert (dirty=true, syncState=PENDING) to the mirror so
     * the UI updates instantly (Phase 5 wires that). Returns the new mutationId.
     */
    suspend fun enqueue(
        op: OutboxOp,
        table: String,
        entityId: String,
        payloadJson: String?,
    ): String = withContext(io) {
        val mutationId = UUID.randomUUID().toString()
        val seq = (outboxDao.maxSeq() ?: 0L) + 1L
        outboxDao.insert(
            OutboxEntity(
                mutationId = mutationId,
                entityTable = table,
                entityId = entityId,
                op = op.name,
                payloadJson = payloadJson,
                originDeviceId = deviceIdProvider.deviceId(),
                seq = seq,
                attempts = 0,
                nextAttemptAt = clock(),
                createdAt = clock(),
            ),
        )
        mutationId
    }

    data class DrainResult(val sent: Int, val failed: Int, val collapsed: Int)

    /**
     * Collapse + replay every due mutation. "Due" = `nextAttemptAt <= now`, so a
     * mutation in backoff is skipped until its window elapses.
     */
    suspend fun drain(): DrainResult = withContext(io) {
        val now = clock()
        val due = outboxDao.listDue(now)
        if (due.isEmpty()) return@withContext DrainResult(0, 0, 0)

        // Collapse per entity (across ALL of the entity's queued rows, not just
        // the due ones, so create→edit→delete collapses correctly even if some
        // rows are mid-backoff). We then only act on entities that have a due row.
        val dueEntityIds = due.map { it.entityId }.toSet()
        val survivors = mutableListOf<OutboxEntity>()
        val collapsedRows = mutableListOf<OutboxEntity>()
        for (entityId in dueEntityIds) {
            val chain = outboxDao.listByEntity(entityId)
            collapsedRows += chain
            OutboxReducer.reduce(chain)?.let { survivors += it }
        }
        survivors.sortBy { it.seq }

        var sent = 0
        var failed = 0
        for (mutation in survivors) {
            val op = OutboxOp.valueOf(mutation.op)
            try {
                val serverLastUpdate = replay.replay(
                    table = mutation.entityTable,
                    op = op,
                    entityId = mutation.entityId,
                    payloadJson = mutation.payloadJson,
                    mutationId = mutation.mutationId,
                    originDeviceId = mutation.originDeviceId,
                )
                // Success: clear the whole entity chain (all collapsed rows) and
                // reconcile the mirror row.
                outboxDao.deleteByEntity(mutation.entityId)
                if (op == OutboxOp.DELETE) {
                    mirror.markArchived(mutation.entityTable, mutation.entityId, serverLastUpdate)
                } else {
                    mirror.markSynced(mutation.entityTable, mutation.entityId, serverLastUpdate)
                }
                sent++
            } catch (t: Throwable) {
                // Failure: back off the originating chain and flag the row FAILED.
                // A terminal 4xx would fail identically on every retry, so park
                // it out of the automatic drain instead (manual retry re-arms it).
                val attempts = mutation.attempts + 1
                val terminal = t is OutboxReplayHttpException && t.isTerminal
                outboxDao.recordFailure(
                    mutationId = mutation.mutationId,
                    attempts = attempts,
                    nextAttemptAt = if (terminal) PARKED_NEXT_ATTEMPT else now + backoffMillis(attempts),
                )
                mirror.markFailed(mutation.entityTable, mutation.entityId)
                failed++
            }
        }

        // Drop pure no-op chains (create→…→delete collapsed to nothing): a due
        // entity with no survivor never reached the server, so clear its queue
        // and hard-delete the optimistic local row.
        val survivorEntityIds = survivors.map { it.entityId }.toSet()
        var collapsed = 0
        for (entityId in dueEntityIds - survivorEntityIds) {
            val table = collapsedRows.first { it.entityId == entityId }.entityTable
            outboxDao.deleteByEntity(entityId)
            mirror.delete(table, entityId)
            collapsed++
        }

        DrainResult(sent = sent, failed = failed, collapsed = collapsed)
    }

    /**
     * Manual retry (D11): make every failed row — exponential backoff or parked
     * on a terminal 4xx — due immediately, so the next [drain] re-attempts it.
     */
    suspend fun rearmFailed(): Unit = withContext(io) {
        outboxDao.rearmFailed(clock())
    }

    companion object {
        const val BASE_BACKOFF_MILLIS = 30_000L // 30s
        const val MAX_BACKOFF_MILLIS = 6L * 60 * 60 * 1000 // 6h ceiling (D10 floor)

        /**
         * `nextAttemptAt` sentinel for terminally-rejected mutations: never due
         * for an automatic drain, only a manual [rearmFailed] revives them.
         */
        const val PARKED_NEXT_ATTEMPT = Long.MAX_VALUE

        /** Exponential backoff: 30s, 60s, 120s, … capped at 6h. */
        fun backoffMillis(attempts: Int): Long {
            val exp = BASE_BACKOFF_MILLIS shl (attempts - 1).coerceIn(0, 20)
            return min(exp, MAX_BACKOFF_MILLIS)
        }
    }
}
