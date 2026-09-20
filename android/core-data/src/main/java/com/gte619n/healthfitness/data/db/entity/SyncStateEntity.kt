package com.gte619n.healthfitness.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * IMPL-AND-20 (Phase 3) — single-row sync cursor table (D6/D13).
 *
 * Holds the one opaque server cursor the client persists, the client-local
 * mirror generation `schemaVersion` (= `SyncEngine.MIRROR_SCHEMA_VERSION` this
 * cursor was built for; when the app advances it by adding a synced collection,
 * `SyncEngine.ensureState` resets the cursor once for a backfilling full scan),
 * and the timestamp of the last completed full sync. Always keyed `id = 0`.
 * (The wire D13 protocol version is separate — `SyncEngine.SYNC_SCHEMA_VERSION`.)
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val cursor: String?,
    val schemaVersion: Int,
    val lastFullSyncAt: Long?,
)
