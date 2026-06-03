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
    fun `unknown collection returns null and is skipped`() {
        assertNull(CollectionRegistry.tableFor("somethingTheClientDoesNotMirrorYet"))
    }
}
