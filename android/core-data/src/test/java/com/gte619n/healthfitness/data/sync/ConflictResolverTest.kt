package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.sync.ConflictResolver.Decision
import com.gte619n.healthfitness.data.sync.ConflictResolver.LocalRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IMPL-AND-20 (Phase 4) — document-level LWW resolution cases (D3).
 */
class ConflictResolverTest {

    @Test
    fun `no local row always applies`() {
        assertEquals(Decision.Apply, ConflictResolver.resolve(incomingLastUpdate = 100, local = null))
    }

    @Test
    fun `newer incoming over clean local applies`() {
        val local = LocalRow(lastUpdate = 100, dirty = false)
        assertEquals(Decision.Apply, ConflictResolver.resolve(200, local))
    }

    @Test
    fun `equal timestamps over clean local applies (server authoritative)`() {
        val local = LocalRow(lastUpdate = 150, dirty = false)
        assertEquals(Decision.Apply, ConflictResolver.resolve(150, local))
    }

    @Test
    fun `older incoming over clean local is rejected`() {
        val local = LocalRow(lastUpdate = 300, dirty = false)
        assertEquals(Decision.Reject, ConflictResolver.resolve(200, local))
    }

    @Test
    fun `newer incoming over dirty local discards the local edit`() {
        val local = LocalRow(lastUpdate = 100, dirty = true)
        assertEquals(Decision.ApplyDiscardingLocal, ConflictResolver.resolve(200, local))
    }

    @Test
    fun `equal incoming over dirty local discards the local edit`() {
        val local = LocalRow(lastUpdate = 100, dirty = true)
        assertEquals(Decision.ApplyDiscardingLocal, ConflictResolver.resolve(100, local))
    }

    @Test
    fun `older incoming over dirty local is rejected (local edit is newer)`() {
        val local = LocalRow(lastUpdate = 300, dirty = true)
        assertEquals(Decision.Reject, ConflictResolver.resolve(200, local))
    }
}
