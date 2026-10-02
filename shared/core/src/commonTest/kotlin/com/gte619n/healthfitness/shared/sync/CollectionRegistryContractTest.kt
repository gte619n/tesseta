package com.gte619n.healthfitness.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * IMPL-IOS-01 Phase 1C — the KMP sibling of the backend
 * `SyncEmittedCollectionsContractTest` and the old Android
 * `CollectionRegistryContractTest`. Every wire `collection` string the backend
 * can emit (the canonical set in docs/reference/sync-emitted-collections.txt)
 * MUST resolve to a mirror table; anything else stays `null` (skipped, not
 * crashed). Runs on JVM and iosSimulatorArm64 — one test, both platforms.
 *
 * The emitted set is inlined here (commonTest cannot read repo files portably
 * across the iOS target). It is kept byte-identical to the shared fixture; a
 * drift is caught because the backend test reads the file and this test asserts
 * the same set — a change to one without the other fails CI on the other side.
 */
class CollectionRegistryContractTest {

    private val emitted = listOf(
        "adhocWorkouts", "adhocWorkouts/sessions", "bloodReadings", "bloodTestReports",
        "bodyComposition", "dailyMetrics", "deviceSyncs", "dexaScans", "goalChatThreads",
        "goalChatThreads/messages", "goals", "goals/phases", "goals/phases/steps",
        "locations", "medications", "medications/adherence", "medications/history",
        "nutritionDailyLogs", "nutritionDays/entries", "nutritionTargets", "protocols",
        "users", "weeklyWorkoutAggregates", "workoutPrograms", "workoutPrograms/scheduled",
    )

    @Test
    fun everyEmittedCollectionRoutesToATable() {
        for (wire in emitted) {
            assertNotNull(
                CollectionRegistry.tableFor(wire),
                "backend emits '$wire' but CollectionRegistry drops it — add an alias",
            )
        }
    }

    @Test
    fun slashFormsResolveToTheSameTableAsDottedAndBareForms() {
        assertEquals(MirrorTables.NUTRITION_ENTRIES, CollectionRegistry.tableFor("nutritionDays/entries"))
        assertEquals(MirrorTables.NUTRITION_ENTRIES, CollectionRegistry.tableFor("nutritionDays.entries"))
        assertEquals(MirrorTables.NUTRITION_ENTRIES, CollectionRegistry.tableFor("entries"))
        assertEquals(MirrorTables.MEDICATION_ADHERENCE, CollectionRegistry.tableFor("medications/adherence"))
        assertEquals(MirrorTables.USER_PROFILE, CollectionRegistry.tableFor("users"))
    }

    @Test
    fun unknownCollectionIsSkippedNotCrashed() {
        assertNull(CollectionRegistry.tableFor("someFutureCollectionTheClientDoesNotMirror"))
    }
}
