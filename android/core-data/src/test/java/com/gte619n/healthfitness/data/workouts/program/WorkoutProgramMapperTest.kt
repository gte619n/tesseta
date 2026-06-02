package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.program.BlockType
import com.gte619n.healthfitness.domain.workouts.program.IntensityKind
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhaseStatus
import com.gte619n.healthfitness.domain.workouts.program.ProgramSource
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class WorkoutProgramMapperTest {

    @Test
    fun `shallow program maps enums and roll-ups`() {
        val dto = WorkoutProgramDto(
            programId = "p1",
            title = "Strength",
            status = "ACTIVE",
            source = "AI_GENERATED",
            startDate = LocalDate.parse("2026-05-01"),
            trainingDays = listOf(DayOfWeek.MON, DayOfWeek.WED),
            totalWeeks = 12,
            phaseCount = 3,
            completedPhaseCount = 1,
            createdAt = Instant.parse("2026-04-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T00:00:00Z"),
        )
        val domain = dto.toDomain()
        assertEquals(ProgramStatus.ACTIVE, domain.status)
        assertEquals(ProgramSource.AI_GENERATED, domain.source)
        assertEquals(12, domain.totalWeeks)
        assertEquals(1 to 3, domain.phaseProgress)
        assertTrue(domain.phases.isEmpty())
    }

    @Test
    fun `unknown enum values degrade to safe fallback`() {
        val dto = WorkoutProgramDto(
            programId = "p1",
            status = "SOMETHING_NEW",
            source = "WEIRD",
            createdAt = Instant.parse("2026-04-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T00:00:00Z"),
        )
        val domain = dto.toDomain()
        assertEquals(ProgramStatus.DRAFT, domain.status)
        assertEquals(ProgramSource.MANUAL, domain.source)
    }

    @Test
    fun `unknown block type degrades to MAIN`() {
        val block = BlockDto(blockId = "b1", type = "FUTURE_TYPE").toDomain()
        assertEquals(BlockType.MAIN, block.type)
    }

    @Test
    fun `deep program carries embedded summary and demo frames`() {
        val dto = WorkoutProgramDeepDto(
            programId = "p1",
            title = "Strength",
            goalId = "g1",
            goalTitle = "Squat goal",
            status = "ACTIVE",
            source = "MANUAL",
            trainingDays = listOf(DayOfWeek.MON),
            createdAt = Instant.parse("2026-04-20T00:00:00Z"),
            updatedAt = Instant.parse("2026-05-01T00:00:00Z"),
            phases = listOf(
                PhaseDto(
                    phaseId = "ph1",
                    title = "Accumulation",
                    orderIndex = 0,
                    status = "COMPLETED",
                    weeks = 4,
                    deloadWeekIndex = 3,
                    days = listOf(
                        WorkoutDayDto(
                            dayId = "d1",
                            label = "Lower",
                            dayOfWeek = DayOfWeek.MON,
                            locationId = "loc1",
                            locationName = "Home",
                            blocks = listOf(
                                BlockDto(
                                    blockId = "b1",
                                    type = "MAIN",
                                    prescriptions = listOf(
                                        PrescriptionDto(
                                            exerciseId = "ex1",
                                            sets = 3,
                                            repsMin = 8,
                                            repsMax = 10,
                                            intensity = IntensityDto("RPE", 8.0),
                                            exercise = ExerciseSummaryDto(
                                                exerciseId = "ex1",
                                                name = "Back Squat",
                                                primaryMuscles = listOf("quadriceps"),
                                                formCues = listOf("Brace"),
                                                demoFrames = listOf(
                                                    DemoFrameDto("START", "https://x/a.jpg"),
                                                    DemoFrameDto(
                                                        phase = "MID",
                                                        imageUrl = null,
                                                        imageCandidates = listOf("https://x/b.jpg"),
                                                    ),
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val domain = dto.toDomain()
        assertEquals("Squat goal", domain.goalTitle)
        assertEquals(4, domain.totalWeeks)
        assertEquals(1, domain.phaseCount)
        assertEquals(1, domain.completedPhaseCount)

        val phase = domain.phases.single()
        assertEquals(ProgramPhaseStatus.COMPLETED, phase.status)
        assertEquals(3, phase.deloadWeekIndex)

        val prescription = phase.days.single().blocks.single().prescriptions.single()
        assertEquals(IntensityKind.RPE, prescription.intensity?.kind)
        assertEquals(8.0, prescription.intensity?.value!!, 0.0)

        val exercise = prescription.exercise!!
        assertEquals("Back Squat", exercise.name)
        // imageUrl falls back to the first imageCandidate when absent.
        assertEquals("https://x/a.jpg", exercise.demoFrames[0].imageUrl)
        assertEquals("https://x/b.jpg", exercise.demoFrames[1].imageUrl)
    }

    @Test
    fun `null intensity deload and exercise are tolerated`() {
        val prescription = PrescriptionDto(
            exerciseId = "ex1",
            sets = 3,
            durationSeconds = 45,
        ).toDomain()
        assertNull(prescription.intensity)
        assertNull(prescription.deloadModifier)
        assertNull(prescription.exercise)
    }

    @Test
    fun `scheduled workout maps status and session day`() {
        val dto = ScheduledWorkoutDto(
            scheduledId = "s1",
            date = LocalDate.parse("2026-06-01"),
            phaseId = "ph1",
            dayId = "d1",
            dayLabel = "Lower",
            weekIndexInPhase = 2,
            isDeload = true,
            status = "PLANNED",
            session = WorkoutDayDto(dayId = "d1", dayOfWeek = DayOfWeek.MON),
        )
        val domain = dto.toDomain()
        assertEquals(ScheduledStatus.PLANNED, domain.status)
        assertTrue(domain.isDeload)
        assertEquals(DayOfWeek.MON, domain.session?.dayOfWeek)
    }
}
