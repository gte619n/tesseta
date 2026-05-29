package com.gte619n.healthfitness.feature.goals

import com.gte619n.healthfitness.domain.goals.Comparator
import com.gte619n.healthfitness.domain.goals.GoalDomain
import com.gte619n.healthfitness.domain.goals.GoalProposal
import com.gte619n.healthfitness.domain.goals.ProposalMetric
import com.gte619n.healthfitness.domain.goals.ProposalPhase
import com.gte619n.healthfitness.domain.goals.ProposalStep
import com.gte619n.healthfitness.domain.goals.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProposalEditTest {

    private val sample = GoalProposal(
        title = "Get LDL under 100",
        description = "Two-phase plan.",
        domain = GoalDomain.CARDIOVASCULAR,
        targetDate = "2026-12-01",
        phases = listOf(
            ProposalPhase(
                title = "Cardio base",
                description = "Build base.",
                targetStartDate = "2026-06-01",
                targetEndDate = "2026-08-01",
                steps = listOf(
                    ProposalStep(
                        title = "Log 24 workouts",
                        kind = StepKind.COUNT,
                        metric = ProposalMetric("workouts.count", Comparator.GTE, 24.0),
                    ),
                    ProposalStep(
                        title = "Hold protein 7d",
                        kind = StepKind.SUSTAINED,
                        metric = ProposalMetric("nutrition.proteinAvg7d", Comparator.GTE, 150.0, windowDays = 7),
                    ),
                    ProposalStep(title = "Manual check-in", kind = StepKind.MANUAL),
                ),
            ),
        ),
    )

    @Test
    fun `round-trips proposal through editable state`() {
        val edit = ProposalEdit.from(sample)
        val out = edit.toProposal()

        assertEquals("Get LDL under 100", out.title)
        assertEquals(GoalDomain.CARDIOVASCULAR, out.domain)
        assertEquals("2026-12-01", out.targetDate)
        assertEquals(1, out.phases.size)

        val steps = out.phases.first().steps
        assertEquals(3, steps.size)

        // COUNT keeps its metric; windowDays stays null.
        val count = steps[0]
        assertEquals(StepKind.COUNT, count.kind)
        assertEquals("workouts.count", count.metric?.metricKey)
        assertEquals(24.0, count.metric?.targetValue)
        assertNull(count.metric?.windowDays)

        // SUSTAINED preserves windowDays.
        val sustained = steps[1]
        assertEquals(7, sustained.metric?.windowDays)

        // MANUAL drops the metric binding entirely.
        val manual = steps[2]
        assertEquals(StepKind.MANUAL, manual.kind)
        assertNull(manual.metric)
    }

    @Test
    fun `editing a field changes the committed proposal`() {
        val edit = ProposalEdit.from(sample)
        edit.title.value = "Edited title"
        edit.phases[0].steps[0].targetValue.value = "40"
        val out = edit.toProposal()

        assertEquals("Edited title", out.title)
        assertEquals(40.0, out.phases[0].steps[0].metric?.targetValue)
    }

    @Test
    fun `switching a step to MANUAL omits the metric on commit`() {
        val edit = ProposalEdit.from(sample)
        edit.phases[0].steps[0].kind.value = StepKind.MANUAL
        val out = edit.toProposal()
        assertNull(out.phases[0].steps[0].metric)
    }

    @Test
    fun `blank editor produces an empty editable structure`() {
        val edit = ProposalEdit.blank()
        assertEquals(GoalDomain.OTHER, edit.domain.value)
        assertEquals(1, edit.phases.size)
        assertEquals(1, edit.phases[0].steps.size)
        assertTrue(edit.toProposal().title!!.isEmpty())
    }
}
