package com.gte619n.healthfitness.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IMPL-IOS-01 Phase 1C — LWW policy, ported from the Android SyncEnginePullTest
 * cases (incl. the [flaky-syncenginepulltest-lww] "dirty local edit that loses
 * LWW" edge). Pure/common → runs identically on JVM and iOS.
 */
class MergeConflictResolverTest {

    @Test
    fun serverNewerWins() {
        assertEquals(
            MergeOutcome.APPLY_SERVER,
            MergeConflictResolver.resolve("2026-06-02T18:00:01Z", "2026-06-02T18:00:00Z", localDirty = false),
        )
    }

    @Test
    fun localNewerIsKept() {
        assertEquals(
            MergeOutcome.KEEP_LOCAL,
            MergeConflictResolver.resolve("2026-06-02T18:00:00Z", "2026-06-02T18:00:01Z", localDirty = false),
        )
    }

    @Test
    fun noLocalRowAlwaysAppliesServer() {
        assertEquals(
            MergeOutcome.APPLY_SERVER,
            MergeConflictResolver.resolve("2026-06-02T18:00:00Z", null, localDirty = true),
        )
    }

    @Test
    fun tieWithDirtyLocalKeepsLocal() {
        assertEquals(
            MergeOutcome.KEEP_LOCAL,
            MergeConflictResolver.resolve("2026-06-02T18:00:00Z", "2026-06-02T18:00:00Z", localDirty = true),
        )
    }

    @Test
    fun tieWithCleanLocalReAppliesServerIdempotently() {
        assertEquals(
            MergeOutcome.APPLY_SERVER,
            MergeConflictResolver.resolve("2026-06-02T18:00:00Z", "2026-06-02T18:00:00Z", localDirty = false),
        )
    }
}
