package com.gte619n.healthfitness.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented proof for baseline DL-3: real fixture rows in the device-only
 * tables (outbox, workout drafts) SURVIVE a forward migration, rather than being
 * silently wiped by a destructive fallback.
 *
 * ⚠️ REQUIRES AN EMULATOR OR DEVICE (real SQLite). Run with:
 *     ./gradlew :core-data:connectedDebugAndroidTest
 *
 * Uses the framework (unencrypted) SQLite openhelper — the migrations are plain
 * SQL and cipher-agnostic, so this validates the DDL + row survival without the
 * Keystore/SQLCipher stack. The migration-chain wiring itself is additionally
 * gated in the fast JVM `HfDatabaseMigrationCoverageTest`.
 */
@RunWith(AndroidJUnit4::class)
class HfDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HfDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun outboxRowSurvivesMigration3To7() {
        // Seed an outbox mutation at v3 — the irreplaceable case: a queued write
        // that exists nowhere else. A destructive fallback would drop it.
        helper.createDatabase(TEST_DB, 3).apply {
            execSQL(
                "INSERT INTO outbox " +
                    "(mutationId, entityTable, entityId, op, payloadJson, originDeviceId, " +
                    "seq, attempts, nextAttemptAt, createdAt) VALUES " +
                    "('m-1', 'medications', 'med-1', 'CREATE', '{\"id\":\"med-1\"}', " +
                    "'device-A', 1, 0, 0, 1000)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, HfDatabase.SCHEMA_VERSION, true, *HfDatabase.ALL_MIGRATIONS,
        )

        db.query("SELECT entityId, op FROM outbox WHERE mutationId = 'm-1'").use { c ->
            assertTrue("outbox row must survive the migration", c.moveToFirst())
            assertEquals("med-1", c.getString(0))
            assertEquals("CREATE", c.getString(1))
        }
        // The tables added along the way exist and are queryable.
        db.query("SELECT COUNT(*) FROM workoutSessionDrafts").use { assertTrue(it.moveToFirst()) }
        db.query("SELECT COUNT(*) FROM catalog_cache").use { assertTrue(it.moveToFirst()) }
        db.query("SELECT COUNT(*) FROM nutritionOps").use { assertTrue(it.moveToFirst()) }
    }

    @Test
    fun workoutDraftSurvivesMigration4To7() {
        // Drafts arrive at v4; seed one there and prove it survives to current.
        helper.createDatabase(TEST_DB, 4).apply {
            execSQL(
                "INSERT INTO workoutSessionDrafts " +
                    "(programId, scheduledId, startedAt, lastActivityAt, status, sessionJson, loggedJson) " +
                    "VALUES ('p1', 's1', 1000, 2000, 'IN_PROGRESS', '{}', '[]')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            TEST_DB, HfDatabase.SCHEMA_VERSION, true,
            HfDatabase.MIGRATION_4_5, HfDatabase.MIGRATION_5_6, HfDatabase.MIGRATION_6_7,
        )

        db.query(
            "SELECT status FROM workoutSessionDrafts WHERE programId = 'p1' AND scheduledId = 's1'",
        ).use { c ->
            assertTrue("draft must survive the migration", c.moveToFirst())
            assertEquals("IN_PROGRESS", c.getString(0))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
