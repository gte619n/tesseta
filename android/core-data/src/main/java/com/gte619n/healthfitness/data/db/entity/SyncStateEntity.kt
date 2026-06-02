package com.gte619n.healthfitness.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * IMPL-AND-20 (Phase 3) — single-row sync cursor table (D6/D13).
 *
 * Holds the one opaque server cursor the client persists, the sync-protocol
 * `schemaVersion` (a bump triggers a Room wipe + full resync, D13), and the
 * timestamp of the last completed full sync. Always keyed `id = 0`.
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val cursor: String?,
    val schemaVersion: Int,
    val lastFullSyncAt: Long?,
)
