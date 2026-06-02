package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.OutboxEntity
import com.gte619n.healthfitness.data.db.entity.OutboxOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IMPL-AND-20 (Phase 4) — exhaustive collapse cases for the pure outbox reducer (D7).
 */
class OutboxReducerTest {

    private var seqCounter = 0L
    private fun row(op: OutboxOp, payload: String?, entityId: String = "e1"): OutboxEntity =
        OutboxEntity(
            mutationId = "m${seqCounter}",
            entityTable = "medications",
            entityId = entityId,
            op = op.name,
            payloadJson = payload,
            originDeviceId = "dev",
            seq = seqCounter++,
            attempts = 0,
            nextAttemptAt = 0,
            createdAt = 0,
        )

    @Test
    fun `empty chain reduces to null`() {
        assertNull(OutboxReducer.reduce(emptyList()))
    }

    @Test
    fun `single create survives unchanged`() {
        val create = row(OutboxOp.CREATE, """{"a":1}""")
        val result = OutboxReducer.reduce(listOf(create))!!
        assertEquals(OutboxOp.CREATE.name, result.op)
        assertEquals("""{"a":1}""", result.payloadJson)
        assertEquals(create.mutationId, result.mutationId)
    }

    @Test
    fun `create then update collapses to a single create with merged (latest) payload`() {
        val create = row(OutboxOp.CREATE, """{"a":1}""")
        val update = row(OutboxOp.UPDATE, """{"a":2}""")
        val result = OutboxReducer.reduce(listOf(create, update))!!
        // Single CREATE keeping the create's identity/seq, latest payload.
        assertEquals(OutboxOp.CREATE.name, result.op)
        assertEquals("""{"a":2}""", result.payloadJson)
        assertEquals(create.mutationId, result.mutationId)
        assertEquals(create.seq, result.seq)
    }

    @Test
    fun `create then update then update keeps the last payload as a create`() {
        val result = OutboxReducer.reduce(
            listOf(
                row(OutboxOp.CREATE, """{"v":1}"""),
                row(OutboxOp.UPDATE, """{"v":2}"""),
                row(OutboxOp.UPDATE, """{"v":3}"""),
            ),
        )!!
        assertEquals(OutboxOp.CREATE.name, result.op)
        assertEquals("""{"v":3}""", result.payloadJson)
    }

    @Test
    fun `create then edit then delete is a no-op`() {
        val result = OutboxReducer.reduce(
            listOf(
                row(OutboxOp.CREATE, """{"a":1}"""),
                row(OutboxOp.UPDATE, """{"a":2}"""),
                row(OutboxOp.DELETE, null),
            ),
        )
        assertNull(result)
    }

    @Test
    fun `create then delete is a no-op`() {
        val result = OutboxReducer.reduce(
            listOf(
                row(OutboxOp.CREATE, """{"a":1}"""),
                row(OutboxOp.DELETE, null),
            ),
        )
        assertNull(result)
    }

    @Test
    fun `update then delete collapses to a single delete`() {
        val update = row(OutboxOp.UPDATE, """{"a":2}""")
        val delete = row(OutboxOp.DELETE, null)
        val result = OutboxReducer.reduce(listOf(update, delete))!!
        assertEquals(OutboxOp.DELETE.name, result.op)
        assertNull(result.payloadJson)
        assertEquals(delete.mutationId, result.mutationId)
    }

    @Test
    fun `update then update keeps the latest update payload`() {
        val first = row(OutboxOp.UPDATE, """{"a":1}""")
        val second = row(OutboxOp.UPDATE, """{"a":9}""")
        val result = OutboxReducer.reduce(listOf(first, second))!!
        assertEquals(OutboxOp.UPDATE.name, result.op)
        assertEquals("""{"a":9}""", result.payloadJson)
        // Keeps the first update's identity so global ordering is stable.
        assertEquals(first.mutationId, result.mutationId)
    }

    @Test
    fun `unordered chain is sorted by seq before reducing`() {
        val create = row(OutboxOp.CREATE, """{"v":1}""")
        val update = row(OutboxOp.UPDATE, """{"v":2}""")
        // Pass them reversed; reducer must still treat create as first.
        val result = OutboxReducer.reduce(listOf(update, create))!!
        assertEquals(OutboxOp.CREATE.name, result.op)
        assertEquals("""{"v":2}""", result.payloadJson)
    }

    @Test
    fun `reduceAll groups by entity and orders survivors by seq`() {
        val rows = listOf(
            row(OutboxOp.CREATE, """{"x":1}""", entityId = "a"),
            row(OutboxOp.CREATE, """{"y":1}""", entityId = "b"),
            row(OutboxOp.UPDATE, """{"x":2}""", entityId = "a"),
            row(OutboxOp.CREATE, """{"z":1}""", entityId = "c"),
            row(OutboxOp.DELETE, null, entityId = "c"), // c collapses to no-op
        )
        val survivors = OutboxReducer.reduceAll(rows)
        assertEquals(2, survivors.size) // a and b survive, c dropped
        assertEquals(listOf("a", "b"), survivors.map { it.entityId })
    }
}
