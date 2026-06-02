package com.gte619n.healthfitness.mobile.sync

import com.gte619n.healthfitness.data.db.dao.SyncStateDao
import com.gte619n.healthfitness.data.db.entity.SyncStateEntity
import com.gte619n.healthfitness.data.sync.SyncEngine
import com.gte619n.healthfitness.data.sync.SyncFlags
import com.gte619n.healthfitness.data.sync.SyncScheduler
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPL-AND-20 (Phase 6) — first-run gate decision tests (D14).
 *
 * The gate must BLOCK a fresh sign-in (no `lastFullSyncAt`) on a brief bounded
 * initial sync, and must NOT block a returning user. Kill-switch (live-network)
 * mode is never gated.
 */
class FirstSyncGateTest {

    private val syncStateDao = mockk<SyncStateDao>()
    private val syncEngine = mockk<SyncEngine>(relaxed = true)
    private val scheduler = mockk<SyncScheduler>(relaxed = true)
    private val syncFlags = mockk<SyncFlags>()

    private fun gate() = FirstSyncGate(syncStateDao, syncEngine, scheduler, syncFlags)

    @Test
    fun `fresh sign-in with no prior full sync needs the gate`() = runTest {
        coEvery { syncFlags.isKillSwitchOn() } returns false
        coEvery { syncStateDao.get() } returns
            SyncStateEntity(cursor = null, schemaVersion = 1, lastFullSyncAt = null)

        assertTrue(gate().needsFirstSync())
    }

    @Test
    fun `fresh sign-in with no sync_state row at all needs the gate`() = runTest {
        coEvery { syncFlags.isKillSwitchOn() } returns false
        coEvery { syncStateDao.get() } returns null

        assertTrue(gate().needsFirstSync())
    }

    @Test
    fun `returning user with a prior full sync does NOT need the gate`() = runTest {
        coEvery { syncFlags.isKillSwitchOn() } returns false
        coEvery { syncStateDao.get() } returns
            SyncStateEntity(cursor = "c", schemaVersion = 1, lastFullSyncAt = 123L)

        assertFalse(gate().needsFirstSync())
    }

    @Test
    fun `kill-switch on is never gated even on a first run`() = runTest {
        coEvery { syncFlags.isKillSwitchOn() } returns true
        // Even if there were no full sync recorded:
        coEvery { syncStateDao.get() } returns
            SyncStateEntity(cursor = null, schemaVersion = 1, lastFullSyncAt = null)

        assertFalse(gate().needsFirstSync())
    }

    @Test
    fun `initial sync runs a bounded pull`() = runTest {
        coEvery { syncEngine.pull(any()) } returns
            SyncEngine.PullResult(1, 0, 0, 0, wiped = false, killSwitch = false)

        gate().runInitialSync()

        coVerify { syncEngine.pull(FirstSyncGate.INITIAL_PAGE_BUDGET) }
    }

    @Test
    fun `initial sync swallows a pull failure so the UI is never wedged`() = runTest {
        coEvery { syncEngine.pull(any()) } throws RuntimeException("network down")
        // Should not throw.
        gate().runInitialSync()
    }

    @Test
    fun `backfill registers the periodic floor and kicks a pull`() {
        every { scheduler.registerPeriodic() } just Runs
        every { scheduler.enqueuePull() } just Runs

        gate().scheduleBackfill()

        verify { scheduler.registerPeriodic() }
        verify { scheduler.enqueuePull() }
    }
}
