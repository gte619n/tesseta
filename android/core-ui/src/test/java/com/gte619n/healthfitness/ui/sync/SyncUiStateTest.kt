package com.gte619n.healthfitness.ui.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * IMPL-AND-20 (Phase 6) — pure state-derivation tests for the global sync
 * indicator ([syncUiStateOf]) and the per-row badge ([badgeSpecOf]). These run on
 * the JVM with no Compose runtime — the mapping is intentionally separated from
 * the @Composable rendering so it is unit-testable.
 */
class SyncUiStateTest {

    @Test
    fun `offline takes priority even with pending writes`() {
        val s = syncUiStateOf(online = false, pendingCount = 3, failedCount = 1, syncing = true)
        assertEquals(SyncIndicatorKind.OFFLINE, s.kind)
        assertEquals(3, s.pendingCount)
    }

    @Test
    fun `failed beats syncing and pending when online`() {
        val s = syncUiStateOf(online = true, pendingCount = 2, failedCount = 1, syncing = true)
        assertEquals(SyncIndicatorKind.FAILED, s.kind)
    }

    @Test
    fun `syncing shown when a drain is in flight and nothing failed`() {
        val s = syncUiStateOf(online = true, pendingCount = 2, failedCount = 0, syncing = true)
        assertEquals(SyncIndicatorKind.SYNCING, s.kind)
    }

    @Test
    fun `pending shown when queued but not draining`() {
        val s = syncUiStateOf(online = true, pendingCount = 2, failedCount = 0, syncing = false)
        assertEquals(SyncIndicatorKind.PENDING, s.kind)
    }

    @Test
    fun `idle when everything synced and online`() {
        val s = syncUiStateOf(online = true, pendingCount = 0, failedCount = 0, syncing = false)
        assertEquals(SyncIndicatorKind.IDLE, s.kind)
    }

    @Test
    fun `updated-elsewhere flag carries through to idle state`() {
        val s = syncUiStateOf(
            online = true, pendingCount = 0, failedCount = 0, syncing = false,
            updatedElsewhere = true,
        )
        assertEquals(SyncIndicatorKind.IDLE, s.kind)
        assertEquals(true, s.updatedElsewhere)
    }

    @Test
    fun `detail surfaces only in the FAILED state (Workstream B)`() {
        val failed = syncUiStateOf(
            online = true, pendingCount = 1, failedCount = 1, syncing = false,
            detail = "HTTP 422: Dose exceeds safe maximum",
        )
        assertEquals(SyncIndicatorKind.FAILED, failed.kind)
        assertEquals("HTTP 422: Dose exceeds safe maximum", failed.detail)

        // A stale detail must not leak into a non-failed state.
        val pending = syncUiStateOf(
            online = true, pendingCount = 1, failedCount = 0, syncing = false,
            detail = "HTTP 422: stale",
        )
        assertEquals(SyncIndicatorKind.PENDING, pending.kind)
        assertNull(pending.detail)
    }

    @Test
    fun `badge maps pending and failed, ignores synced and unknown`() {
        assertEquals(BadgeSpec.PENDING, badgeSpecOf("PENDING"))
        assertEquals(BadgeSpec.FAILED, badgeSpecOf("FAILED"))
        assertEquals(BadgeSpec.PENDING, badgeSpecOf("pending")) // case-insensitive
        assertNull(badgeSpecOf("SYNCED"))
        assertNull(badgeSpecOf(null))
        assertNull(badgeSpecOf("anything-else"))
    }
}
