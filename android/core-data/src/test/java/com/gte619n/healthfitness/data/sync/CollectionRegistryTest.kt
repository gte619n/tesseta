package com.gte619n.healthfitness.data.sync

import com.gte619n.healthfitness.data.db.entity.MirrorTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** IMPL-AND-20 (Phase 4) — collection→table dispatch map. */
class CollectionRegistryTest {

    @Test
    fun `canonical table names map to themselves`() {
        MirrorTables.ALL.forEach { table ->
            assertEquals(table, CollectionRegistry.tableFor(table))
        }
    }

    @Test
    fun `subcollection aliases resolve to flat mirror tables`() {
        assertEquals(MirrorTables.MEDICATION_ADHERENCE, CollectionRegistry.tableFor("adherence"))
        assertEquals(MirrorTables.GOAL_STEPS, CollectionRegistry.tableFor("steps"))
        assertEquals(MirrorTables.GOAL_CHAT_MESSAGES, CollectionRegistry.tableFor("messages"))
        assertEquals(MirrorTables.NUTRITION_ENTRIES, CollectionRegistry.tableFor("entries"))
        assertEquals(MirrorTables.USER_PROFILE, CollectionRegistry.tableFor("users"))
    }

    @Test
    fun `backend slash-form subcollection strings resolve to flat mirror tables`() {
        // The FirestoreSyncChangeReader emits the full Firestore path for
        // subcollections (its SUBCOLLECTIONS `emitted` values). tableFor does an
        // exact-match lookup on that raw string, so each one must be aliased —
        // otherwise the pulled change is skipped and never reaches the mirror
        // (the web→phone nutrition sync bug: entries logged on another device
        // never landed because only the dotted/bare forms were registered).
        assertEquals(MirrorTables.NUTRITION_ENTRIES, CollectionRegistry.tableFor("nutritionDays/entries"))
        assertEquals(MirrorTables.MEDICATION_ADHERENCE, CollectionRegistry.tableFor("medications/adherence"))
        assertEquals(MirrorTables.MEDICATION_HISTORY, CollectionRegistry.tableFor("medications/history"))
        assertEquals(MirrorTables.GOAL_PHASES, CollectionRegistry.tableFor("goals/phases"))
        assertEquals(MirrorTables.GOAL_STEPS, CollectionRegistry.tableFor("goals/phases/steps"))
        assertEquals(MirrorTables.GOAL_CHAT_MESSAGES, CollectionRegistry.tableFor("goalChatThreads/messages"))
        assertEquals(MirrorTables.WORKOUT_SCHEDULED, CollectionRegistry.tableFor("workoutPrograms/scheduled"))
    }

    @Test
    fun `unknown collection returns null and is skipped`() {
        assertNull(CollectionRegistry.tableFor("somethingTheClientDoesNotMirrorYet"))
    }
}
