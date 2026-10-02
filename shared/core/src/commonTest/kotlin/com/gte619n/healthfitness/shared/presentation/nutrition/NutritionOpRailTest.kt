package com.gte619n.healthfitness.shared.presentation.nutrition

import com.gte619n.healthfitness.shared.data.AdjustProposal
import com.gte619n.healthfitness.shared.data.AdjustStatus
import com.gte619n.healthfitness.shared.data.Leftover
import com.gte619n.healthfitness.shared.data.LeftoverProposal
import com.gte619n.healthfitness.shared.data.LeftoverStatus
import com.gte619n.healthfitness.shared.data.MealAdjustment
import com.gte619n.healthfitness.shared.data.NutritionOp
import com.gte619n.healthfitness.shared.data.NutritionOpType
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IMPL-IOS-01 Phase 3 Wave C — the op-rail + adjust/leftover state machine. Pure
 * unit tests (no dispatcher needed) over [withPendingOps] and [PendingNutritionOp]
 * mapping — the XPLAT-shared logic both clients depend on.
 */
class NutritionOpRailTest {

    // ---- withPendingOps projection ----

    @Test
    fun captureOpAddsSyntheticRowToTargetMeal() {
        val day = sampleDay(entries = listOf(sampleEntry(entryId = "e-1", meal = "BREAKFAST")))
        val ops = listOf(op(type = NutritionOpType.CAPTURE_PHOTO, meal = "LUNCH"))
        val out = day.withPendingOps(ops, "2026-09-23")!!
        val lunch = out.meals.first { it.meal == "LUNCH" }
        assertEquals(1, lunch.entries.size)
        assertTrue(lunch.entries.single().entryId.startsWith(PENDING_CAPTURE_PREFIX))
    }

    @Test
    fun opsForOtherDatesAreIgnored() {
        val day = sampleDay()
        val ops = listOf(op(type = NutritionOpType.CAPTURE_PHOTO, meal = "LUNCH", date = "2020-01-01"))
        assertEquals(day, day.withPendingOps(ops, "2026-09-23"))
    }

    @Test
    fun leftoverAndAdjustOpsAddNoSyntheticRow() {
        val day = sampleDay(entries = listOf(sampleEntry(entryId = "e-1", meal = "DINNER")))
        val ops = listOf(
            op(type = NutritionOpType.REMOVE_LEFTOVERS, meal = "DINNER", target = "e-1"),
            op(type = NutritionOpType.ADJUST_MEAL, meal = "DINNER", target = "e-1"),
        )
        val out = day.withPendingOps(ops, "2026-09-23")!!
        // Only the original entry — decoration happens via EntryState, not a new row.
        assertEquals(1, out.meals.flatMap { it.entries }.size)
    }

    @Test
    fun nullDayWithCaptureOpMaterializesADay() {
        val out = (null as com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay?)
            .withPendingOps(listOf(op(type = NutritionOpType.DESCRIBE_ASYNC, meal = "SNACK")), "2026-09-23")
        assertTrue(out!!.meals.single().entries.single().entryId.startsWith(PENDING_CAPTURE_PREFIX))
    }

    // ---- PendingNutritionOp mapping ----

    @Test
    fun adjustStatusMapsToLifecycle() {
        assertEquals(PendingNutritionOp.PENDING, AdjustStatus.ADJUSTING.toPendingOp(hasProposal = false))
        assertEquals(PendingNutritionOp.PENDING_REVIEW, AdjustStatus.PENDING_REVIEW.toPendingOp(hasProposal = true))
        // PENDING_REVIEW with no proposal falls back to PENDING (still analyzing).
        assertEquals(PendingNutritionOp.PENDING, AdjustStatus.PENDING_REVIEW.toPendingOp(hasProposal = false))
        assertEquals(PendingNutritionOp.PENDING_RETAKE, AdjustStatus.REJECTED.toPendingOp(hasProposal = false))
    }

    @Test
    fun leftoverStatusMapsToLifecycle() {
        assertEquals(PendingNutritionOp.PENDING, LeftoverStatus.ANALYZING.toPendingOp(hasProposal = false))
        assertEquals(PendingNutritionOp.PENDING_REVIEW, LeftoverStatus.PENDING_REVIEW.toPendingOp(hasProposal = true))
        assertEquals(PendingNutritionOp.PENDING_RETAKE, LeftoverStatus.REJECTED.toPendingOp(hasProposal = false))
        assertEquals(PendingNutritionOp.APPLIED, LeftoverStatus.APPLIED.toPendingOp(hasProposal = false))
    }

    @Test
    fun carrierProjectionsUseProposalPresence() {
        val adjusting = MealAdjustment(status = AdjustStatus.PENDING_REVIEW, proposal = null)
        assertEquals(PendingNutritionOp.PENDING, adjusting.pendingOp())

        val ready = MealAdjustment(
            status = AdjustStatus.PENDING_REVIEW,
            proposal = AdjustProposal(mealName = "Bowl", newTotals = Macros.EMPTY, oldTotals = Macros.EMPTY),
        )
        assertEquals(PendingNutritionOp.PENDING_REVIEW, ready.pendingOp())

        val leftoverReady = Leftover(
            status = LeftoverStatus.PENDING_REVIEW,
            proposal = LeftoverProposal(),
        )
        assertEquals(PendingNutritionOp.PENDING_REVIEW, leftoverReady.pendingOp())
    }

    private fun op(
        type: NutritionOpType,
        meal: String,
        date: String = "2026-09-23",
        target: String? = null,
    ) = NutritionOp(
        id = "op-${type.name}",
        type = type,
        date = date,
        mealWire = meal,
        label = "logging…",
        targetEntryId = target,
    )
}
