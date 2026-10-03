package com.gte619n.healthfitness.shared.sync

import com.gte619n.healthfitness.shared.db.MirrorDatabase

/**
 * IMPL-IOS-01 Phase 1C — platform SQLite driver seam. The [MirrorDatabase] schema
 * is common (SQLDelight-generated), but the driver that opens the file is
 * per-platform (NativeSqliteDriver on iOS, JDBC on JVM). [open] creates the driver,
 * applies the schema, and returns the ready database.
 */
expect class MirrorDatabaseFactory {
    fun open(): MirrorDatabase
}
