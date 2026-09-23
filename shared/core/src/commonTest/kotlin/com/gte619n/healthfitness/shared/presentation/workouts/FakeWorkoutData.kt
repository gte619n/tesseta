package com.gte619n.healthfitness.shared.presentation.workouts

import com.gte619n.healthfitness.shared.data.AdHocLibraryItem
import com.gte619n.healthfitness.shared.data.AdHocLibraryRepository
import com.gte619n.healthfitness.shared.data.WorkoutProgramRepository
import com.gte619n.healthfitness.shared.data.WorkoutSessionRepository
import com.gte619n.healthfitness.shared.data.WorkoutStreakSettingsRepository
import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.workouts.program.Block
import com.gte619n.healthfitness.shared.domain.workouts.program.BlockType
import com.gte619n.healthfitness.shared.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.shared.domain.workouts.program.NutritionGuidance
import com.gte619n.healthfitness.shared.domain.workouts.program.Prescription
import com.gte619n.healthfitness.shared.domain.workouts.program.ProgramPhase
import com.gte619n.healthfitness.shared.domain.workouts.program.ProgramPhaseStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ProgramSource
import com.gte619n.healthfitness.shared.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutHistoryPage
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.shared.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * IMPL-IOS-01 Phase 3 Wave D — commonTest fakes/fixtures for the workouts VMs.
 * Plain KMP fakes (no MockK); author the vertical against the shared
 * WorkoutRepositories interfaces before the concrete Room/Ktor store lands.
 */

private val EPOCH = Instant.fromEpochSeconds(0)

fun sampleExercise(id: String = "ex-1", name: String = "Back Squat"): ExerciseSummary =
    ExerciseSummary(
        exerciseId = id,
        name = name,
        primaryMuscles = listOf("quads"),
        formCues = listOf("brace"),
        demoFrames = emptyList(),
    )

fun samplePrescription(
    exerciseId: String = "ex-1",
    sets: Int? = 4,
    repsMin: Int? = 8,
    repsMax: Int? = 10,
    targetWeightLbs: Double? = 135.0,
    exercise: ExerciseSummary? = sampleExercise(exerciseId),
): Prescription = Prescription(
    exerciseId = exerciseId,
    orderIndex = 0,
    sets = sets,
    repsMin = repsMin,
    repsMax = repsMax,
    durationSeconds = null,
    intensity = null,
    restSeconds = 90,
    tempo = null,
    notes = null,
    deloadModifier = null,
    exercise = exercise,
    targetWeightLbs = targetWeightLbs,
)

fun sampleDay(
    dayId: String = "day-1",
    label: String = "Lower A",
    prescriptions: List<Prescription> = listOf(samplePrescription()),
): WorkoutDay = WorkoutDay(
    dayId = dayId,
    label = label,
    dayOfWeek = DayOfWeek.MON,
    locationId = "loc-1",
    locationName = "Home Gym",
    orderIndex = 0,
    blocks = listOf(
        Block(blockId = "blk-1", type = BlockType.MAIN, title = "Main", orderIndex = 0, prescriptions = prescriptions),
    ),
)

fun samplePhase(
    phaseId: String = "phase-1",
    days: List<WorkoutDay> = listOf(sampleDay()),
): ProgramPhase = ProgramPhase(
    phaseId = phaseId,
    title = "Base",
    focus = "hypertrophy",
    orderIndex = 0,
    status = ProgramPhaseStatus.ACTIVE,
    weeks = 4,
    deloadWeekIndex = null,
    targetStartDate = null,
    targetEndDate = null,
    days = days,
)

fun sampleProgram(
    programId: String = "prog-1",
    title: String = "Strength Block",
    status: ProgramStatus = ProgramStatus.ACTIVE,
    startDate: LocalDate? = LocalDate(2026, 9, 1),
    updatedAt: Instant = Instant.fromEpochSeconds(1_700_000_000),
    phases: List<ProgramPhase> = listOf(samplePhase()),
): WorkoutProgram = WorkoutProgram(
    programId = programId,
    title = title,
    description = "A block",
    goalId = null,
    status = status,
    source = ProgramSource.MANUAL,
    startDate = startDate,
    trainingDays = listOf(DayOfWeek.MON, DayOfWeek.WED, DayOfWeek.FRI),
    createdAt = EPOCH,
    updatedAt = updatedAt,
    phases = phases,
)

fun sampleScheduled(
    scheduledId: String,
    date: LocalDate,
    status: ScheduledStatus = ScheduledStatus.COMPLETED,
    programId: String = "prog-1",
    phaseId: String = "phase-1",
    dayId: String = "day-1",
): ScheduledWorkout = ScheduledWorkout(
    scheduledId = scheduledId,
    date = date,
    phaseId = phaseId,
    dayId = dayId,
    dayLabel = "Lower A",
    weekIndexInPhase = 0,
    isDeload = false,
    locationId = "loc-1",
    locationName = "Home Gym",
    status = status,
    programId = programId,
)

/** Configurable fake backing the browse VMs. */
class FakeWorkoutProgramRepository(
    val programs: MutableStateFlow<List<WorkoutProgram>> = MutableStateFlow(listOf(sampleProgram())),
    private val calendar: MutableStateFlow<List<ScheduledWorkout>> = MutableStateFlow(emptyList()),
    private val allCompleted: MutableStateFlow<List<ScheduledWorkout>> = MutableStateFlow(emptyList()),
    private val programFlows: MutableMap<String, MutableStateFlow<WorkoutProgram?>> = mutableMapOf(),
    var lastSets: Map<String, List<LoggedSet>> = emptyMap(),
    var lastSetsFails: Boolean = false,
    var historyPage: WorkoutHistoryPage? = null,
    var runDayResult: Result<String> = Result.success("sched-today"),
) : WorkoutProgramRepository {

    fun setCalendar(items: List<ScheduledWorkout>) { calendar.value = items }
    fun setAllCompleted(items: List<ScheduledWorkout>) { allCompleted.value = items }
    fun programFlow(id: String): MutableStateFlow<WorkoutProgram?> =
        programFlows.getOrPut(id) { MutableStateFlow(programs.value.firstOrNull { it.programId == id }) }

    override fun observePrograms(): Flow<List<WorkoutProgram>> = programs
    override fun observeProgram(programId: String): Flow<WorkoutProgram?> = programFlow(programId)
    override fun observeCalendar(programId: String, from: LocalDate, to: LocalDate): Flow<List<ScheduledWorkout>> = calendar
    override fun observeAllCompleted(from: LocalDate, to: LocalDate): Flow<List<ScheduledWorkout>> = allCompleted

    override suspend fun refresh() {}
    override suspend fun activate(programId: String): Result<Unit> = Result.success(Unit)
    override suspend fun updateDetails(programId: String, title: String, description: String?): Result<WorkoutProgram> =
        Result.success(sampleProgram(programId = programId, title = title))
    override suspend fun nutritionGuidance(programId: String): Result<NutritionGuidance?> = Result.success(null)
    override suspend fun applyNutritionTarget(programId: String): Result<Macros> = Result.success(Macros.EMPTY)
    override suspend fun runDayToday(programId: String, phaseId: String, dayId: String): Result<String> = runDayResult

    override suspend fun workoutHistoryPage(page: Int, pageSize: Int): Result<WorkoutHistoryPage> =
        historyPage?.let { Result.success(it) } ?: Result.success(WorkoutHistoryPage(emptyList(), page, 0, false))
    override fun cachedFirstHistoryPage(): WorkoutHistoryPage? = null

    override suspend fun lastSetsFor(programId: String, exerciseId: String): Result<List<LoggedSet>> =
        if (lastSetsFails) Result.failure(RuntimeException("offline"))
        else Result.success(lastSets[exerciseId] ?: emptyList())
}

/** Fake session repo; this agent only reads drafts/parked. */
class FakeWorkoutSessionRepository(
    val drafts: MutableStateFlow<List<WorkoutSessionDraft>> = MutableStateFlow(emptyList()),
    val parked: MutableStateFlow<List<ParkedCompletion>> = MutableStateFlow(emptyList()),
    var resetResult: Result<Unit> = Result.success(Unit),
) : WorkoutSessionRepository {
    override fun observeDrafts(): Flow<List<WorkoutSessionDraft>> = drafts
    override fun observeParkedCompletions(): Flow<List<ParkedCompletion>> = parked
    override suspend fun reset(programId: String, scheduledId: String): Result<Unit> = resetResult
    override suspend fun restoreParked(programId: String, scheduledId: String): Result<Unit> = Result.success(Unit)
    override suspend fun discardParked(programId: String, scheduledId: String): Result<Unit> = Result.success(Unit)
}

class FakeWorkoutSettingsRepository(
    val target: MutableStateFlow<Int> = MutableStateFlow(WorkoutStreakSettings.DEFAULT_WEEKLY_TARGET),
) : WorkoutStreakSettingsRepository {
    override val weeklyStreakTarget: Flow<Int> = target
    override suspend fun refresh() {}
}

class FakeAdHocLibraryRepository(
    val items: MutableStateFlow<List<AdHocLibraryItem>> = MutableStateFlow(emptyList()),
) : AdHocLibraryRepository {
    override fun observeLibrary(): Flow<List<AdHocLibraryItem>> = items
}
