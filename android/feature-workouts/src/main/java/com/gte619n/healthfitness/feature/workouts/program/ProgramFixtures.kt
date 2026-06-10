package com.gte619n.healthfitness.feature.workouts.program

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.program.Block
import com.gte619n.healthfitness.domain.workouts.program.BlockType
import com.gte619n.healthfitness.domain.workouts.program.DeloadModifier
import com.gte619n.healthfitness.domain.workouts.program.DemoFrame
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.Intensity
import com.gte619n.healthfitness.domain.workouts.program.IntensityKind
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhase
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhaseStatus
import com.gte619n.healthfitness.domain.workouts.program.ProgramSource
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.domain.workouts.session.DraftStatus
import com.gte619n.healthfitness.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.domain.workouts.session.WorkoutSessionDraft
import java.time.Instant
import java.time.LocalDate

/** Sample data for @Preview / snapshot tests. Not used at runtime. */
internal object ProgramFixtures {

    private val squat = ExerciseSummary(
        exerciseId = "ex-squat",
        name = "Back Squat",
        primaryMuscles = listOf("quadriceps", "glutes", "hamstrings"),
        formCues = listOf(
            "Brace your core before descending.",
            "Keep knees tracking over toes.",
            "Drive through mid-foot on the way up.",
        ),
        demoFrames = listOf(
            DemoFrame("START", "https://example.com/squat-start.jpg"),
            DemoFrame("MID", "https://example.com/squat-mid.jpg"),
            DemoFrame("END", "https://example.com/squat-end.jpg"),
        ),
    )

    private val plank = ExerciseSummary(
        exerciseId = "ex-plank",
        name = "Plank",
        primaryMuscles = listOf("core", "abdominals"),
        formCues = listOf("Keep a straight line from head to heels."),
        demoFrames = emptyList(),
    )

    private val mainBlock = Block(
        blockId = "b-main",
        type = BlockType.MAIN,
        title = "Main",
        orderIndex = 1,
        prescriptions = listOf(
            Prescription(
                exerciseId = "ex-squat",
                orderIndex = 0,
                sets = 3,
                repsMin = 8,
                repsMax = 10,
                durationSeconds = null,
                intensity = Intensity(IntensityKind.RPE, 8.0),
                restSeconds = 90,
                tempo = "3-1-1",
                notes = "Last set AMRAP.",
                deloadModifier = DeloadModifier(setsMultiplier = 0.6, intensityDelta = -1.0),
                exercise = squat,
            ),
        ),
    )

    private val coreBlock = Block(
        blockId = "b-core",
        type = BlockType.CORE,
        title = "Core",
        orderIndex = 2,
        prescriptions = listOf(
            Prescription(
                exerciseId = "ex-plank",
                orderIndex = 0,
                sets = 3,
                repsMin = null,
                repsMax = null,
                durationSeconds = 45,
                intensity = null,
                restSeconds = 30,
                tempo = null,
                notes = null,
                deloadModifier = null,
                exercise = plank,
            ),
        ),
    )

    private val day = WorkoutDay(
        dayId = "d1",
        label = "Lower A",
        dayOfWeek = DayOfWeek.MON,
        locationId = "g1",
        locationName = "Home Gym",
        orderIndex = 0,
        blocks = listOf(mainBlock, coreBlock),
    )

    val deepProgram = WorkoutProgram(
        programId = "p1",
        title = "12-Week Strength Base",
        description = "A periodized strength block building toward a 1RM test.",
        goalId = "goal-1",
        goalTitle = "Squat 1.5× bodyweight",
        status = ProgramStatus.ACTIVE,
        source = ProgramSource.AI_GENERATED,
        startDate = LocalDate.parse("2026-05-01"),
        trainingDays = listOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI),
        createdAt = Instant.parse("2026-04-20T00:00:00Z"),
        updatedAt = Instant.parse("2026-05-01T00:00:00Z"),
        totalWeeks = 12,
        phaseCount = 3,
        completedPhaseCount = 1,
        phases = listOf(
            ProgramPhase(
                phaseId = "ph1",
                title = "Accumulation",
                focus = "Volume",
                orderIndex = 0,
                status = ProgramPhaseStatus.COMPLETED,
                weeks = 4,
                deloadWeekIndex = 3,
                targetStartDate = LocalDate.parse("2026-05-01"),
                targetEndDate = LocalDate.parse("2026-05-28"),
                days = listOf(day),
            ),
            ProgramPhase(
                phaseId = "ph2",
                title = "Intensification",
                focus = "Intensity",
                orderIndex = 1,
                status = ProgramPhaseStatus.ACTIVE,
                weeks = 4,
                deloadWeekIndex = 3,
                targetStartDate = LocalDate.parse("2026-05-29"),
                targetEndDate = LocalDate.parse("2026-06-25"),
                days = listOf(day),
            ),
            ProgramPhase(
                phaseId = "ph3",
                title = "Peak",
                focus = "Realization",
                orderIndex = 2,
                status = ProgramPhaseStatus.LOCKED,
                weeks = 4,
                deloadWeekIndex = null,
                targetStartDate = LocalDate.parse("2026-06-26"),
                targetEndDate = LocalDate.parse("2026-07-23"),
                days = listOf(day),
            ),
        ),
    )

    val listPrograms: List<WorkoutProgram> = listOf(
        deepProgram.copy(phases = emptyList()),
        deepProgram.copy(
            programId = "p2",
            title = "Hypertrophy Builder",
            status = ProgramStatus.DRAFT,
            goalId = null,
            goalTitle = null,
            phases = emptyList(),
            completedPhaseCount = 0,
        ),
    )

    val thisWeek: List<ScheduledWorkout> = listOf(
        ScheduledWorkout(
            scheduledId = "s1",
            date = LocalDate.parse("2026-06-01"),
            phaseId = "ph2",
            dayId = "d1",
            dayLabel = "Lower A",
            weekIndexInPhase = 1,
            isDeload = false,
            locationId = "g1",
            locationName = "Home Gym",
            status = ScheduledStatus.COMPLETED,
        ),
        ScheduledWorkout(
            scheduledId = "s2",
            date = LocalDate.parse("2026-06-03"),
            phaseId = "ph2",
            dayId = "d2",
            dayLabel = "Upper A",
            weekIndexInPhase = 1,
            isDeload = false,
            locationId = "g1",
            locationName = "Home Gym",
            status = ScheduledStatus.PLANNED,
        ),
        ScheduledWorkout(
            scheduledId = "s3",
            date = LocalDate.parse("2026-06-05"),
            phaseId = "ph2",
            dayId = "d3",
            dayLabel = "Lower B",
            weekIndexInPhase = 1,
            isDeload = true,
            locationId = "g1",
            locationName = "Home Gym",
            status = ScheduledStatus.PLANNED,
        ),
    )

    // ADR-0012 (IMPL-AND-16) session-logger fixtures.

    /** A PLANNED scheduled session carrying its full day (deep calendar shape). */
    val scheduledWithSession: ScheduledWorkout = thisWeek[1].copy(session = day)

    /**
     * An in-progress local draft over [scheduledWithSession] with the first
     * squat set already checked off.
     */
    val activeDraft = WorkoutSessionDraft(
        programId = "p1",
        scheduledId = scheduledWithSession.scheduledId,
        startedAt = Instant.parse("2026-06-03T14:00:00Z"),
        lastActivityAt = Instant.parse("2026-06-03T14:05:00Z"),
        status = DraftStatus.ACTIVE,
        scheduled = scheduledWithSession,
        logged = mapOf(
            PrescriptionKey("b-main", 0) to listOf(
                LoggedSet(
                    weightLbs = 135.0,
                    reps = 8,
                    rpe = 8.0,
                    restSeconds = null,
                    completedAt = Instant.parse("2026-06-03T14:05:00Z"),
                ),
            ),
        ),
    )

    /**
     * A finished session whose completion upload the server terminally
     * rejected (IMPL-16 A10/Q3) — surfaced as the "couldn't sync — restore to
     * review" banner.
     */
    val parkedCompletion = ParkedCompletion(
        programId = "p1",
        scheduledId = scheduledWithSession.scheduledId,
        status = ScheduledStatus.COMPLETED,
        completedAt = Instant.parse("2026-06-03T15:00:00Z"),
        loggedSetCount = 9,
        orphanedSetCount = 2,
        sessionAvailable = true,
        dayLabel = "Upper A",
    )
}
