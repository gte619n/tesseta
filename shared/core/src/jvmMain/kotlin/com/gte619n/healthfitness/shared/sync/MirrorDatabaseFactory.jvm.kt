package com.gte619n.healthfitness.shared.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.gte619n.healthfitness.shared.db.MirrorDatabase

/**
 * JVM driver — backs the sync-engine tests with an in-memory SQLite DB (the schema
 * is created on open). Not used on device; iOS has its own actual.
 */
actual class MirrorDatabaseFactory {
    actual fun open(): MirrorDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MirrorDatabase.Schema.create(driver)
        return MirrorDatabase(driver)
    }
}
