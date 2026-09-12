package com.gte619n.healthfitness.domain.nutrition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMPL-LEFTOVER-01 — pure derivations on [Entry] that gate the "Remove Leftovers"
 * button (spec D5) and drive the badge / review affordances (D7/D8/D17).
 */
class LeftoverEligibilityTest {

    private fun ingredient(name: String, grams: Double = 100.0) = EntryIngredient(
        name = name,
        servingGrams = grams,
        quantity = 1.0,
        macros = Macros(caloriesKcal = 100.0),
    )

    private fun composite(
        id: String = "e1",
        analysisStatus: String = "READY",
        leftover: Leftover? = null,
    ) = Entry(
        entryId = id,
        meal = "DINNER",
        foodName = "Salmon plate",
        quantity = 1.0,
        macros = Macros(caloriesKcal = 720.0),
        source = "PHOTO",
        analysisStatus = analysisStatus,
        ingredients = listOf(ingredient("rice"), ingredient("salmon")),
        leftover = leftover,
    )

    private fun single(id: String = "s1") = Entry(
        entryId = id,
        meal = "LUNCH",
        foodName = "Protein bar",
        quantity = 1.0,
        macros = Macros(caloriesKcal = 200.0),
        source = "BARCODE",
    )

    @Test
    fun `composite photo meal is leftover-eligible`() {
        assertTrue(composite().isLeftoverEligible)
    }

    @Test
    fun `single food (barcode) meal is NOT leftover-eligible`() {
        assertFalse(single().isLeftoverEligible)
    }

    @Test
    fun `a still-analyzing photo is NOT eligible`() {
        assertFalse(composite(analysisStatus = "ANALYZING").isLeftoverEligible)
    }

    @Test
    fun `an entry mid leftover analysis is NOT eligible`() {
        val e = composite(leftover = Leftover(status = LeftoverStatus.ANALYZING))
        assertFalse(e.isLeftoverEligible)
        assertTrue(e.isAnalyzingLeftovers)
    }

    @Test
    fun `a synthetic in-flight capture row is NOT eligible`() {
        assertFalse(composite(id = "pending-capture-xyz").isLeftoverEligible)
    }

    @Test
    fun `an APPLIED entry is still eligible (re-run) and shows the badge`() {
        val e = composite(leftover = Leftover(status = LeftoverStatus.APPLIED, servedMacros = Macros(720.0)))
        assertTrue(e.isLeftoverEligible)
        assertTrue(e.hasAppliedLeftover)
    }

    @Test
    fun `PENDING_REVIEW with a proposal surfaces the review affordance`() {
        val e = composite(
            leftover = Leftover(
                status = LeftoverStatus.PENDING_REVIEW,
                proposal = LeftoverProposal(consumedTotals = Macros(540.0)),
            ),
        )
        assertTrue(e.hasLeftoverReview)
        assertFalse(e.hasAppliedLeftover)
    }

    @Test
    fun `PENDING_REVIEW without a proposal does not surface review`() {
        val e = composite(leftover = Leftover(status = LeftoverStatus.PENDING_REVIEW, proposal = null))
        assertFalse(e.hasLeftoverReview)
    }

    @Test
    fun `served-ate derivation reads consumed from live macros and served from baseline`() {
        // Live macros = consumed (D9); served baseline preserved in leftover.
        val e = composite(
            leftover = Leftover(
                status = LeftoverStatus.APPLIED,
                servedMacros = Macros(caloriesKcal = 720.0),
                servedIngredients = listOf(
                    LeftoverServedIngredient("rice", servingGrams = 150.0, quantity = 1.0),
                ),
            ),
        ).copy(macros = Macros(caloriesKcal = 540.0))

        assertEquals(720.0, e.leftover!!.servedMacros!!.caloriesKcal!!, 0.001)
        assertEquals(540.0, e.macros.caloriesKcal!!, 0.001)
        assertEquals("rice", e.leftover.servedIngredients.single().name)
    }
}
