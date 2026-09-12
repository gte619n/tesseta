package com.gte619n.healthfitness.data.nutrition

import com.gte619n.healthfitness.data.sync.SyncTestMoshi
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.LeftoverStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPL-LEFTOVER-01 — the new `leftover` field on the entry wire type must parse
 * from both the REST `EntryResponse` shape and the nested sync-delta `leftover`
 * map, and stay back-compatible (an entry with no `leftover` key parses unchanged).
 * Also covers the REMOVE_LEFTOVERS op-payload round-trip.
 */
class NutritionLeftoverParseTest {

    private val moshi = SyncTestMoshi.instance
    private val entryAdapter = moshi.adapter(Entry::class.java)
    private val leftoverPayloadAdapter = moshi.adapter(RemoveLeftoversPayload::class.java)

    @Test
    fun `entry without a leftover field parses (back-compat)`() {
        val json = """
            {"entryId":"e1","meal":"DINNER","foodName":"Oatmeal","quantity":1.0,
             "macros":{"caloriesKcal":300.0},"source":"CATALOG"}
        """.trimIndent()
        val entry = entryAdapter.fromJson(json)!!
        assertNull(entry.leftover)
        assertEquals(300.0, entry.macros.caloriesKcal!!, 0.001)
    }

    @Test
    fun `PENDING_REVIEW EntryResponse leftover parses with its proposal`() {
        val json = """
            {"entryId":"e1","meal":"DINNER","foodName":"Salmon plate","quantity":1.0,
             "macros":{"caloriesKcal":720.0},"source":"PHOTO",
             "ingredients":[{"name":"rice","servingGrams":150.0,"quantity":1.0,"macros":{"caloriesKcal":200.0}}],
             "leftover":{
               "status":"PENDING_REVIEW",
               "servedMacros":{"caloriesKcal":720.0},
               "servedIngredients":[{"name":"rice","servingGrams":150.0,"quantity":1.0,"macros":{"caloriesKcal":200.0}}],
               "proposal":{
                 "items":[
                   {"name":"rice","servedGrams":150.0,"consumedGrams":70.0,"remainingGrams":80.0,"matched":true,"consumedMacros":{"caloriesKcal":93.0}},
                   {"name":"salmon","servedGrams":140.0,"consumedGrams":120.0,"remainingGrams":20.0,"matched":true,"consumedMacros":{"caloriesKcal":180.0}}
                 ],
                 "servedTotals":{"caloriesKcal":720.0},
                 "consumedTotals":{"caloriesKcal":540.0},
                 "overallConfidence":0.82,
                 "warning":false,
                 "warningNote":null
               }
             }}
        """.trimIndent()
        val entry = entryAdapter.fromJson(json)!!
        val leftover = entry.leftover
        assertNotNull(leftover)
        assertEquals(LeftoverStatus.PENDING_REVIEW, leftover!!.status)
        assertEquals(720.0, leftover.servedMacros!!.caloriesKcal!!, 0.001)
        assertEquals("rice", leftover.servedIngredients.single().name)
        val proposal = leftover.proposal!!
        assertEquals(2, proposal.items.size)
        assertEquals(70.0, proposal.items.first().consumedGrams!!, 0.001)
        assertEquals(540.0, proposal.consumedTotals!!.caloriesKcal!!, 0.001)
        assertFalse(proposal.warning)
        assertTrue(entry.hasLeftoverReview)
    }

    @Test
    fun `APPLIED entry parses and reports the badge + served baseline`() {
        val json = """
            {"entryId":"e1","meal":"DINNER","foodName":"Salmon plate","quantity":1.0,
             "macros":{"caloriesKcal":540.0},"source":"PHOTO",
             "ingredients":[{"name":"rice","servingGrams":70.0,"quantity":1.0,"macros":{"caloriesKcal":93.0}}],
             "leftover":{"status":"APPLIED","servedMacros":{"caloriesKcal":720.0},
               "servedIngredients":[{"name":"rice","servingGrams":150.0,"quantity":1.0,"macros":{"caloriesKcal":200.0}}],
               "proposal":null}}
        """.trimIndent()
        val entry = entryAdapter.fromJson(json)!!
        assertTrue(entry.hasAppliedLeftover)
        assertEquals(540.0, entry.macros.caloriesKcal!!, 0.001)
        assertEquals(720.0, entry.leftover!!.servedMacros!!.caloriesKcal!!, 0.001)
    }

    @Test
    fun `a partial-mismatch proposal carries a warning note`() {
        val json = """
            {"entryId":"e1","meal":"DINNER","foodName":"Plate","quantity":1.0,
             "macros":{"caloriesKcal":700.0},"source":"PHOTO",
             "leftover":{"status":"PENDING_REVIEW","servedMacros":{"caloriesKcal":700.0},
               "servedIngredients":[],
               "proposal":{"items":[{"name":"rice","servedGrams":150.0,"consumedGrams":150.0,"remainingGrams":0.0,"matched":false,"consumedMacros":{"caloriesKcal":200.0}}],
                 "servedTotals":{"caloriesKcal":700.0},"consumedTotals":{"caloriesKcal":700.0},
                 "overallConfidence":0.5,"warning":true,"warningNote":"Couldn't match rice"}}}
        """.trimIndent()
        val entry = entryAdapter.fromJson(json)!!
        val proposal = entry.leftover!!.proposal!!
        assertTrue(proposal.warning)
        assertEquals("Couldn't match rice", proposal.warningNote)
        assertFalse(proposal.items.single().matched)
    }

    @Test
    fun `REMOVE_LEFTOVERS op payload round-trips the target entryId`() {
        val json = leftoverPayloadAdapter.toJson(RemoveLeftoversPayload("entry-42"))
        val back = leftoverPayloadAdapter.fromJson(json)!!
        assertEquals("entry-42", back.targetEntryId)
    }
}
