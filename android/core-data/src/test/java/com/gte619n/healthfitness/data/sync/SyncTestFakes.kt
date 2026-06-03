package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.dao.OutboxDao
import com.gte619n.healthfitness.data.db.dao.SyncStateDao
import com.gte619n.healthfitness.data.db.entity.OutboxEntity
import com.gte619n.healthfitness.data.db.entity.SyncStateEntity
import com.gte619n.healthfitness.data.net.DayOfWeekMoshiAdapter
import com.gte619n.healthfitness.data.net.InstantAdapter
import com.gte619n.healthfitness.data.net.LocalDateAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Moshi mirroring NetworkModule, for the pure-JVM sync tests. */
internal object SyncTestMoshi {
    val instance: Moshi = Moshi.Builder()
        .add(LocalDateAdapter())
        .add(InstantAdapter())
        .add(DayOfWeekMoshiAdapter())
        .add(KotlinJsonAdapterFactory())
        .build()
}

/**
 * In-memory [MirrorOps] so the SyncEngine/OutboxRepository run on the pure JVM
 * (no Room, no SQLCipher, no device/Robolectric). Keyed by "table:id".
 */
internal open class FakeMirrorOps : MirrorOps {
    val rows = linkedMapOf<String, MirrorRowData>()
    private fun key(table: String, id: String) = "$table:$id"

    override suspend fun runInTransaction(block: suspend () -> Unit) = block()

    override suspend fun upsert(table: String, row: MirrorRowData) {
        rows[key(table, row.id)] = row
    }

    override suspend fun markArchived(table: String, id: String, lastUpdate: Long) {
        val cur = rows[key(table, id)]
        rows[key(table, id)] = (cur ?: MirrorRowData(id, "{}", lastUpdate, "ARCHIVED", false, "SYNCED"))
            .copy(status = "ARCHIVED", lastUpdate = lastUpdate)
    }

    override suspend fun delete(table: String, id: String) {
        rows.remove(key(table, id))
    }

    override suspend fun getRow(table: String, id: String): MirrorRowData? = rows[key(table, id)]

    override suspend fun markSynced(table: String, id: String, lastUpdate: Long) {
        rows[key(table, id)]?.let {
            rows[key(table, id)] = it.copy(lastUpdate = lastUpdate, dirty = false, syncState = "SYNCED")
        }
    }

    override suspend fun markFailed(table: String, id: String) {
        rows[key(table, id)]?.let { rows[key(table, id)] = it.copy(syncState = "FAILED") }
    }

    override suspend fun clearDirty(table: String, id: String, lastUpdate: Long) {
        rows[key(table, id)]?.let {
            rows[key(table, id)] = it.copy(lastUpdate = lastUpdate, dirty = false, syncState = "SYNCED")
        }
    }
}

/** In-memory single-row [SyncStateDao]. */
internal class FakeSyncStateDao : SyncStateDao {
    private val state = MutableStateFlow<SyncStateEntity?>(null)

    override fun observe(): Flow<SyncStateEntity?> = state
    override suspend fun get(): SyncStateEntity? = state.value
    override suspend fun upsert(state: SyncStateEntity) { this.state.value = state }
    override suspend fun updateCursor(cursor: String?) {
        state.value = state.value?.copy(cursor = cursor)
            ?: SyncStateEntity(cursor = cursor, schemaVersion = 1, lastFullSyncAt = null)
    }
    override suspend fun clear() { state.value = null }
}

/** Records a wipe + lets the test assert it. */
internal class FakeDbWiper : DbWiper {
    var wipes = 0
    override suspend fun wipeMirrors() { wipes++ }
}

/** Records kill-switch writes. */
internal class FakeKillSwitchSink : KillSwitchSink {
    val writes = mutableListOf<Boolean>()
    override suspend fun setKillSwitch(on: Boolean) { writes += on }
}

/** In-memory [OutboxDao] backed by a list. */
internal class FakeOutboxDao : OutboxDao {
    val store = mutableListOf<OutboxEntity>()
    private val pending = MutableStateFlow(0)

    override suspend fun insert(row: OutboxEntity) {
        store.removeAll { it.mutationId == row.mutationId }
        store += row
        pending.value = store.size
    }
    override suspend fun listDue(now: Long): List<OutboxEntity> =
        store.filter { it.nextAttemptAt <= now }.sortedBy { it.seq }
    override fun observePendingCount(): Flow<Int> = pending
    override fun observeFailedCount(): Flow<Int> = pending.map { store.count { row -> row.attempts > 0 } }
    override suspend fun listByEntity(entityId: String): List<OutboxEntity> =
        store.filter { it.entityId == entityId }.sortedBy { it.seq }
    override suspend fun listAll(): List<OutboxEntity> = store.sortedBy { it.seq }
    override suspend fun maxSeq(): Long? = store.maxOfOrNull { it.seq }
    override suspend fun recordFailure(mutationId: String, attempts: Int, nextAttemptAt: Long) {
        val i = store.indexOfFirst { it.mutationId == mutationId }
        if (i >= 0) store[i] = store[i].copy(attempts = attempts, nextAttemptAt = nextAttemptAt)
    }
    override suspend fun deleteById(mutationId: String) {
        store.removeAll { it.mutationId == mutationId }; pending.value = store.size
    }
    override suspend fun deleteByEntity(entityId: String) {
        store.removeAll { it.entityId == entityId }; pending.value = store.size
    }
    override suspend fun clear() { store.clear(); pending.value = 0 }
}

/**
 * A [DeviceIdProvider] returning a fixed id without touching DataStore.
 * Built with mockk so the test stays pure-JVM.
 */
internal fun fakeDeviceIdProvider(id: String = "test-device"): DeviceIdProvider {
    val provider = io.mockk.mockk<DeviceIdProvider>()
    io.mockk.coEvery { provider.deviceId() } returns id
    return provider
}
