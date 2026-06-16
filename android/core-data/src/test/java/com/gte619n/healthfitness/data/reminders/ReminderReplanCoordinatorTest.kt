package com.gte619n.healthfitness.data.reminders

import com.gte619n.healthfitness.data.db.dao.MedicationDao
import com.gte619n.healthfitness.data.db.entity.MedicationEntity
import com.gte619n.healthfitness.data.sync.SyncSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * IMPL-STAB Workstream F (items 1 & 2): the coordinator must re-plan when the
 * medication mirror changes and when a relevant sync push lands, but stay quiet
 * for irrelevant pushes and the initial mirror emission.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderReplanCoordinatorTest {

    private val rows = MutableStateFlow<List<MedicationEntity>>(emptyList())
    private val dao = FakeMedicationDao(rows)
    private val signals = SyncSignals()
    private val replans = AtomicInteger(0)

    private fun coordinator(scope: CoroutineScope) = ReminderReplanCoordinator(
        medicationDao = dao,
        syncSignals = signals,
        scope = scope,
        replan = { replans.incrementAndGet() },
    ).also { it.start() }

    @Test
    fun replansOnMedicationMirrorChange_butNotInitialEmission() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coordinator(CoroutineScope(dispatcher))
        advanceUntilIdle()
        // Initial replay emission is dropped — no replan yet.
        assertEquals(0, replans.get())

        rows.value = listOf(row("m1", lastUpdate = 1L))
        advanceTimeBy(ReminderReplanCoordinator.DEBOUNCE_MILLIS + 50)
        advanceUntilIdle()
        assertEquals(1, replans.get())

        // A genuine change (same id, new lastUpdate) re-plans again after debounce.
        rows.value = listOf(row("m1", lastUpdate = 2L))
        advanceTimeBy(ReminderReplanCoordinator.DEBOUNCE_MILLIS + 50)
        advanceUntilIdle()
        assertEquals(2, replans.get())
    }

    @Test
    fun debounceCoalescesABurstOfWrites() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coordinator(CoroutineScope(dispatcher))
        advanceUntilIdle()

        rows.value = listOf(row("m1", 1L))
        advanceTimeBy(100)
        rows.value = listOf(row("m1", 2L), row("m2", 1L))
        advanceTimeBy(100)
        rows.value = listOf(row("m1", 3L))
        advanceTimeBy(ReminderReplanCoordinator.DEBOUNCE_MILLIS + 50)
        advanceUntilIdle()
        // Three rapid changes within the debounce window → a single replan.
        assertEquals(1, replans.get())
    }

    @Test
    fun replansOnRelevantSyncPush_ignoresIrrelevant() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        coordinator(CoroutineScope(dispatcher))
        advanceUntilIdle()

        signals.onSyncPush("[\"medications\"]")
        advanceUntilIdle()
        assertEquals(1, replans.get())

        signals.onSyncPush("[\"medicationReminderSettings\"]")
        advanceUntilIdle()
        assertEquals(2, replans.get())

        // A null hint is treated as "could be relevant".
        signals.onSyncPush(null)
        advanceUntilIdle()
        assertEquals(3, replans.get())

        // An unrelated collection does not trigger a replan.
        signals.onSyncPush("[\"foodEntries\"]")
        advanceUntilIdle()
        assertEquals(3, replans.get())
    }

    private fun row(id: String, lastUpdate: Long) = MedicationEntity(
        id = id,
        payloadJson = "{}",
        lastUpdate = lastUpdate,
        status = "ACTIVE",
        dirty = false,
        syncState = "SYNCED",
    )

    private class FakeMedicationDao(
        private val flow: MutableStateFlow<List<MedicationEntity>>,
    ) : MedicationDao {
        override fun observeActive(): Flow<List<MedicationEntity>> = flow
        override suspend fun getById(id: String): MedicationEntity? = flow.value.firstOrNull { it.id == id }
        override suspend fun upsert(row: MedicationEntity) = error("unused")
        override suspend fun upsertAll(rows: List<MedicationEntity>) = error("unused")
        override suspend fun markArchived(id: String, lastUpdate: Long) = error("unused")
        override suspend fun delete(id: String) = error("unused")
    }
}
