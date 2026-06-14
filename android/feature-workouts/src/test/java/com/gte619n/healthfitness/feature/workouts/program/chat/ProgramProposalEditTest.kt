package com.gte619n.healthfitness.feature.workouts.program.chat

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.program.Block
import com.gte619n.healthfitness.domain.workouts.program.BlockType
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.Intensity
import com.gte619n.healthfitness.domain.workouts.program.IntensityKind
import com.gte619n.healthfitness.domain.workouts.program.NutritionGuidance
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhase
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhaseStatus
import com.gte619n.healthfitness.domain.workouts.program.ProgramSource
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class ProgramProposalEditTest {

    private fun prescription(
        exerciseId: String,
        targetWeightLbs: Double?,
        loadBasis: String?,
    ) = Prescription(
        exerciseId = exerciseId,
        orderIndex = 0,
        sets = 3,
        repsMin = 5,
        repsMax = 5,
        durationSeconds = null,
        intensity = Intensity(IntensityKind.RPE, 8.0),
        restSeconds = 120,
        tempo = null,
        notes = null,
        deloadModifier = null,
        exercise = ExerciseSummary(exerciseId, "Bench Press", emptyList(), emptyList(), emptyList()),
        targetWeightLbs = targetWeightLbs,
        loadBasis = loadBasis,
    )

    private val program = WorkoutProgram(
        programId = "",
        title = "5-Week Ease-In",
        description = "Restarting TRT, easing back in.",
        goalId = null,
        status = ProgramStatus.DRAFT,
        source = ProgramSource.AI_ASSISTED,
        startDate = LocalDate.parse("2026-06-15"),
        trainingDays = listOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        nutritionGuidance = NutritionGuidance(kcal = 2600, proteinG = 180, note = "Slight surplus."),
        phases = listOf(
            ProgramPhase(
                phaseId = "ph1",
                title = "Accumulation",
                focus = "Volume",
                orderIndex = 0,
                status = ProgramPhaseStatus.LOCKED,
                weeks = 2,
                deloadWeekIndex = null,
                targetStartDate = LocalDate.parse("2026-06-15"),
                targetEndDate = LocalDate.parse("2026-06-29"),
                nutritionGuidance = NutritionGuidance(kcal = 2700, proteinG = 185, carbsG = 300, fatG = 70),
                days = listOf(
                    WorkoutDay(
                        dayId = "d1",
                        label = "Upper A",
                        dayOfWeek = DayOfWeek.MON,
                        locationId = "g1",
                        locationName = "Home Gym",
                        orderIndex = 0,
                        blocks = listOf(
                            Block(
                                blockId = "b1",
                                type = BlockType.MAIN,
                                title = "Main",
                                orderIndex = 0,
                                prescriptions = listOf(
                                    prescription("ex-bench", 185.0, "e1RM 225 × 80%, −10% layoff"),
                                    prescription("ex-row", null, null),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun `round-trips the proposal through editable state, preserving IMPL-18 fields`() {
        val edit = ProgramProposalEdit.from(program)
        val out = edit.toProgram()

        assertEquals("5-Week Ease-In", out.title)
        assertEquals(LocalDate.parse("2026-06-15"), out.startDate)
        // Program-level nutrition survives.
        assertEquals(2600, out.nutritionGuidance?.kcal)

        val phase = out.phases.single()
        // Per-phase nutrition survives.
        assertEquals(2700, phase.nutritionGuidance?.kcal)
        assertEquals(70, phase.nutritionGuidance?.fatG)

        val rxs = phase.days.single().blocks.single().prescriptions
        // targetWeightLbs + loadBasis round-trip on the prescription with a load.
        assertEquals(185.0, rxs[0].targetWeightLbs)
        assertEquals("e1RM 225 × 80%, −10% layoff", rxs[0].loadBasis)
        // The RPE-fallback prescription keeps a null concrete load.
        assertNull(rxs[1].targetWeightLbs)
    }

    @Test
    fun `editing the prescribed weight changes the committed program`() {
        val edit = ProgramProposalEdit.from(program)
        edit.title.value = "Edited title"
        // Edit the first prescription's weight, and set the un-logged one.
        val phaseEdit = edit.phases.single()
        val rxEdits = phaseEdit.days.single().blocks.single().prescriptions
        rxEdits[0].targetWeightLbs.value = "195"
        rxEdits[1].targetWeightLbs.value = "135"

        val out = edit.toProgram()
        assertEquals("Edited title", out.title)
        val rxs = out.phases.single().days.single().blocks.single().prescriptions
        assertEquals(195.0, rxs[0].targetWeightLbs)
        assertEquals(135.0, rxs[1].targetWeightLbs)
    }

    @Test
    fun `clearing a weight commits a null concrete load (RPE fallback)`() {
        val edit = ProgramProposalEdit.from(program)
        val rxEdit = edit.phases.single().days.single().blocks.single().prescriptions[0]
        rxEdit.targetWeightLbs.value = ""
        val out = edit.toProgram()
        assertNull(out.phases.single().days.single().blocks.single().prescriptions[0].targetWeightLbs)
    }
}
