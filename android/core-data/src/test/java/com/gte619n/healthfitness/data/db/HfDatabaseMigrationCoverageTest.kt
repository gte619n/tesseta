package com.gte619n.healthfitness.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast JVM guard for baseline DL-3: a Room `version` bump WITHOUT a matching
 * migration used to silently destroy the outbox + workout drafts (data that
 * lives nowhere else) via `fallbackToDestructiveMigration()`. That's now gone;
 * this test additionally makes the mistake impossible to merge by asserting the
 * registered migration chain actually reaches [HfDatabase.SCHEMA_VERSION].
 *
 * Runs in the standard `testDebugUnitTest` gate (no device needed) so it fails
 * in PR CI, not only on the emulator. Row-level survival is proven separately by
 * the instrumented `HfDatabaseMigrationTest`.
 */
class HfDatabaseMigrationCoverageTest {

    private val migrations = HfDatabase.ALL_MIGRATIONS.sortedBy { it.startVersion }

    @Test
    fun `registered migrations form a contiguous chain reaching the current schema version`() {
        // Each migration's end is the next one's start — no gaps, no overlaps.
        for (i in 1 until migrations.size) {
            assertEquals(
                "migration chain has a gap before ${migrations[i].startVersion}",
                migrations[i - 1].endVersion,
                migrations[i].startVersion,
            )
        }
        assertEquals(
            "the migration chain must reach SCHEMA_VERSION — bump added without a migration?",
            HfDatabase.SCHEMA_VERSION,
            migrations.last().endVersion,
        )
    }

    @Test
    fun `no v3-plus version is permitted to wipe destructively on upgrade`() {
        // The outbox + drafts arrive at v3/v4; only the pre-outbox pre-release
        // schemas may ever wipe. Freeze that guarantee.
        assertTrue(
            "DESTRUCTIVE_FALLBACK_FROM must never include a version >= 3 (would risk DL-3)",
            HfDatabase.DESTRUCTIVE_FALLBACK_FROM.all { it < 3 },
        )
    }
}
