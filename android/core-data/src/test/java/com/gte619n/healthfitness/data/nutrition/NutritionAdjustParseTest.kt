package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.data.sync.SyncTestMoshi
import com.gte619n.healthfitness.domain.nutrition.AdjustStatus
import com.gte619n.healthfitness.domain.nutrition.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The async "Adjust with AI" `adjustment` field on the entry wire type must parse
 * from the REST `EntryResponse` shape (and the nested sync-delta `adjustment` map),
 * stay back-compatible (an entry with no `adjustment` key parses unchanged), and the
 * ADJUST_MEAL op-payload must round-trip.
 */
class NutritionAdjustParseTest {

    private val moshi = SyncTestMoshi.instance
    private val entryAdapter = moshi.adapter(Entry::class.java)
    private val adjustPayloadAdapter = moshi.adapter(AdjustMealPayload::class.java)

    @Test
    fun `entry without an adjustment field parses (back-compat)`() {
        val json = """
            {"entryId":"e1","meal":"DINNER","foodName":"Oatmeal","quantity":1.0,
             "macros":{"caloriesKcal":300.0},"source":"CATALOG"}
        """.trimIndent()
        val entry = entryAdapter.fromJson(json)!!
        assertNull(entry.adjustment)
        assertFalse(entry.isAdjusting)
        assertFalse(entry.hasAdjustReview)
    }

    @Test
    fun `ADJUSTING adjustment parses`() {
        val json = """
            {"entryId":"e1","meal":"DINNER","foodName":"Lentils and rice","quantity":1.0,
             "macros":{"caloriesKcal":500.0},"source":"PHOTO",
             "adjustment":{"status":"ADJUSTING","instruction":"swap lentils for couscous"}}
        """.trimIndent()
        val entry = entryAdapter.fromJson(json)!!
        assertEquals(AdjustStatus.ADJUSTING, entry.adjustment!!.status)
        assertTrue(entry.isAdjusting)
        assertFalse(entry.hasAdjustReview)
    }

    @Test
    fun `PENDING_REVIEW adjustment parses with its proposal`() {
        val json = """
            {"entryId":"e1","meal":"DINNER","foodName":"Lentils and rice","quantity":1.0,
             "macros":{"caloriesKcal":500.0},"source":"PHOTO",
             "adjustment":{
               "status":"PENDING_REVIEW",
               "instruction":"swap lentils for couscous",
               "proposal":{
                 "mealName":"Pearl couscous and rice",
                 "packagedProduct":false,
                 "items":[
                   {"name":"Pearl couscous","servingLabel":"150 g","servingGrams":150.0,"macros":{"caloriesKcal":170.0}},
                   {"name":"White rice","servingLabel":"100 g","servingGrams":100.0,"macros":{"caloriesKcal":130.0}}
                 ],
                 "newTotals":{"caloriesKcal":300.0},
                 "oldTotals":{"caloriesKcal":500.0}
               }
             }}
        """.trimIndent()
        val entry = entryAdapter.fromJson(json)!!
        val adjustment = entry.adjustment
        assertNotNull(adjustment)
        assertEquals(AdjustStatus.PENDING_REVIEW, adjustment!!.status)
        val proposal = adjustment.proposal!!
        assertEquals("Pearl couscous and rice", proposal.mealName)
        assertEquals(2, proposal.items.size)
        assertEquals(300.0, proposal.newTotals.caloriesKcal!!, 0.001)
        assertEquals(500.0, proposal.oldTotals.caloriesKcal!!, 0.001)
        assertTrue(entry.hasAdjustReview)
    }

    @Test
    fun `ADJUST_MEAL op payload round-trips`() {
        val json = adjustPayloadAdapter.toJson(AdjustMealPayload("entry-42", "fix it", true))
        val back = adjustPayloadAdapter.fromJson(json)!!
        assertEquals("entry-42", back.targetEntryId)
        assertEquals("fix it", back.instruction)
        assertTrue(back.saveAsMeal)
    }
}
