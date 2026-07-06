package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.OutboxEntity
import com.gte619n.healthfitness.data.db.entity.OutboxOp

/**
 * IMPL-AND-20 (Phase 4) — pure outbox reducer (D7).
 *
 * Before the outbox drains, the chain of queued mutations for a single
 * `entityId` is collapsed to its net effect so the server sees the minimum
 * number of (idempotent) writes:
 *
 *  - `CREATE` then any number of `UPDATE`s  ⇒ a single `CREATE` whose payload is
 *    the *last* payload (later edits supersede the create body — the merged
 *    create). The surviving row keeps the original `CREATE`'s `mutationId`
 *    (idempotency key) and `seq` so per-entity ordering against *other* entities
 *    is preserved.
 *  - `CREATE` … `DELETE`  ⇒ **no-op**: the entity never reached the server, so
 *    there is nothing to create and nothing to delete. The whole chain drops.
 *  - `UPDATE`(s) then `DELETE`  ⇒ a single `DELETE` (the prior updates are moot;
 *    the server row will be archived). Keeps the `DELETE`'s identity.
 *  - `UPDATE` then `UPDATE`  ⇒ a single `UPDATE` with the latest payload.
 *
 * This is intentionally a **pure function** over the already-`seq`-ordered chain
 * for one entity, with **no Android / DB / coroutine dependencies**, so it is the
 * primary exhaustive unit-test target.
 *
 * Input contract: [chain] is the full list of outbox rows for ONE `entityId`,
 * sorted ascending by `seq` (as [com.gte619n.healthfitness.data.db.dao.OutboxDao.listByEntity]
 * returns it). Output: zero or one surviving [OutboxEntity] (the net mutation),
 * or `null` when the chain collapses to nothing.
 */
object OutboxReducer {

    /** Reduce one entity's mutation chain to its single net mutation, or null. */
    fun reduce(chain: List<OutboxEntity>): OutboxEntity? {
        if (chain.isEmpty()) return null
        val ordered = chain.sortedBy { it.seq }

        val first = ordered.first()
        val last = ordered.last()
        val firstOp = OutboxOp.valueOf(first.op)
        val lastOp = OutboxOp.valueOf(last.op)

        // Chain ends in DELETE.
        if (lastOp == OutboxOp.DELETE) {
            // CREATE + … + DELETE: created locally then deleted, all before reaching
            // the server ⇒ nothing to create and nothing to delete ⇒ drop entirely.
            if (firstOp == OutboxOp.CREATE) return null
            // … + DELETE (entity already existed on the server) ⇒ a single DELETE.
            return last.copy(payloadJson = null)
        }

        val latestPayload = ordered.lastOrNull { it.payloadJson != null }?.payloadJson
            ?: last.payloadJson

        // Chain ends in CREATE. This is a re-establishment of a *reused* id — an
        // undo(DELETE)-then-redo(CREATE), e.g. medication-adherence undo + re-log of
        // the same (med, date, window). The net op is a CREATE carrying the
        // create's own identity (its idempotency key) + the latest payload; the
        // prior delete is moot. Emitting an UPDATE here (the old behavior) targeted
        // an update endpoint the resource may not even have (adherence is POST/
        // DELETE only), so a re-log after an undo failed. A plain CREATE-only chain
        // also lands here (first == last), giving the same single CREATE as before.
        if (lastOp == OutboxOp.CREATE) {
            return last.copy(payloadJson = latestPayload)
        }

        // Chain ends in UPDATE. It's a CREATE when the entity was first established
        // locally in this chain (the server never had it), otherwise an UPDATE.
        // Keep the first row's identity (mutationId/seq) so the create's idempotency
        // key and the entity's global ordering are stable.
        return first.copy(
            op = if (firstOp == OutboxOp.CREATE) OutboxOp.CREATE.name else OutboxOp.UPDATE.name,
            payloadJson = latestPayload,
        )
    }

    /**
     * Collapse a flat list of outbox rows spanning many entities into the set of
     * surviving net mutations, ordered by `seq` (drain order). Groups by
     * `entityId`, reduces each group, drops no-ops.
     */
    fun reduceAll(rows: List<OutboxEntity>): List<OutboxEntity> =
        rows.groupBy { it.entityId }
            .values
            .mapNotNull { reduce(it) }
            .sortedBy { it.seq }
}
