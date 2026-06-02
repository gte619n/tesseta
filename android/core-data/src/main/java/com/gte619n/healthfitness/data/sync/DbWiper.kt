package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.HfDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IMPL-AND-20 (Phase 4) — wipes the mirror + cursor in place for a schemaVersion
 * bump (D13), without deleting the encrypted DB file (that is sign-out's job via
 * `DbWipe`). A schema bump must clear all rows and reset the cursor so the next
 * pull does a clean full resync.
 *
 * Interface-backed so [SyncEngine] tests can supply an in-memory fake and run on
 * the pure JVM (no Room / SQLCipher / device).
 */
interface DbWiper {
    /** Truncate every mirror table (and structural tables) for a full resync. */
    suspend fun wipeMirrors()
}

@Singleton
class RoomDbWiper @Inject constructor(
    private val database: HfDatabase,
) : DbWiper {
    /**
     * `clearAllTables()` truncates every table and resets autoincrement; the
     * sync layer then re-seeds `sync_state` with an empty cursor. The outbox is
     * cleared too — on a schema bump any pending local mutations are encoded
     * against the old schema and must be re-derived, not blindly replayed.
     */
    override suspend fun wipeMirrors() {
        database.clearAllTables()
    }
}
