package com.gte619n.healthfitness.data.workouts.program

import com.gte619n.healthfitness.domain.common.DayOfWeek
import com.gte619n.healthfitness.domain.workouts.program.Block
import com.gte619n.healthfitness.domain.workouts.program.BlockType
import com.gte619n.healthfitness.domain.workouts.program.DeloadModifier
import com.gte619n.healthfitness.domain.workouts.program.DemoFrame
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.Intensity
import com.gte619n.healthfitness.domain.workouts.program.IntensityKind
import com.gte619n.healthfitness.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhase
import com.gte619n.healthfitness.domain.workouts.program.ProgramPhaseStatus
import com.gte619n.healthfitness.domain.workouts.program.ProgramSource
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.domain.workouts.program.WorkoutProgram
import java.time.Instant
import java.time.LocalDate

// Wire mirrors of the IMPL-15 read responses. Decoded by the base Moshi's
// reflective KotlinJsonAdapterFactory plus the java.time + DayOfWeek adapters
// registered in NetworkModule. `LocalDate`/`Instant`/`DayOfWeek` decode via
// those adapters; enums are parsed with safe fallback in the mapper so an
// unknown server value degrades rather than crashes the whole parse.

data class IntensityDto(
    val kind: String,
    val value: Double? = null,
)

data class DeloadModifierDto(
    val setsMultiplier: Double? = null,
    val intensityDelta: Double? = null,
)

data class DemoFrameDto(
    val phase: String,
    val imageUrl: String? = null,
    val imageCandidates: List<String> = emptyList(),
)

data class ExerciseSummaryDto(
    val exerciseId: String,
    val name: String,
    val primaryMuscles: List<String> = emptyList(),
    val formCues: List<String> = emptyList(),
    val demoFrames: List<DemoFrameDto> = emptyList(),
)

/**
 * One performed set's full actuals (ADR-0012 Decision 2 / IMPL-17 D3). Every
 * field is nullable so imported-history rows (weight-only) stay valid and the
 * logger keeps everything beyond weight/reps skippable.
 */
data class LoggedSetDto(
    val weightLbs: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val restSeconds: Int? = null,
    val completedAt: Instant? = null,
)

data class PrescriptionDto(
    val exerciseId: String,
    val orderIndex: Int = 0,
    val sets: Int? = null,
    val repsMin: Int? = null,
    val repsMax: Int? = null,
    val durationSeconds: Int? = null,
    val intensity: IntensityDto? = null,
    val restSeconds: Int? = null,
    val tempo: String? = null,
    val notes: String? = null,
    val deloadModifier: DeloadModifierDto? = null,
    val exercise: ExerciseSummaryDto? = null,
    // Nullable: the backend sends `"loggedSets": null` for unlogged prescriptions
    // (Spring MVC emits explicit nulls), which would throw against a non-null List.
    val loggedSets: List<LoggedSetDto>? = null,
)

data class BlockDto(
    val blockId: String,
    val type: String,
    // Nullable: Spring MVC serializes explicit nulls, so the wire may carry
    // `"title": null` (and likewise for the other defaulted strings below).
    // A non-null Kotlin field would make Moshi throw and fail the whole decode.
    val title: String? = null,
    val orderIndex: Int = 0,
    val prescriptions: List<PrescriptionDto> = emptyList(),
)

data class WorkoutDayDto(
    val dayId: String,
    val label: String? = null,
    val dayOfWeek: DayOfWeek,
    // Imported-history session days have a null locationId; the backend emits it
    // as explicit JSON null, so this MUST be nullable or the decode blows up.
    val locationId: String? = null,
    val locationName: String? = null,
    val orderIndex: Int = 0,
    val blocks: List<BlockDto> = emptyList(),
)

data class PhaseDto(
    val phaseId: String,
    val title: String? = null,
    val focus: String? = null,
    val orderIndex: Int = 0,
    val status: String,
    val weeks: Int = 0,
    val deloadWeekIndex: Int? = null,
    val targetStartDate: LocalDate? = null,
    val targetEndDate: LocalDate? = null,
    val days: List<WorkoutDayDto> = emptyList(),
)

/** Shallow list shape — no phases/days. */
data class WorkoutProgramDto(
    val programId: String,
    val title: String? = null,
    val description: String? = null,
    val goalId: String? = null,
    val status: String,
    val source: String,
    val startDate: LocalDate? = null,
    val trainingDays: List<DayOfWeek> = emptyList(),
    val totalWeeks: Int = 0,
    val phaseCount: Int = 0,
    val completedPhaseCount: Int = 0,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Deep shape — phases → days → blocks → prescriptions + embedded summaries. */
data class WorkoutProgramDeepDto(
    val programId: String,
    val title: String? = null,
    val description: String? = null,
    val goalId: String? = null,
    val goalTitle: String? = null,
    val status: String,
    val source: String,
    val startDate: LocalDate? = null,
    val trainingDays: List<DayOfWeek> = emptyList(),
    val phases: List<PhaseDto> = emptyList(),
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
)

data class ScheduledWorkoutDto(
    val scheduledId: String,
    val date: LocalDate,
    val phaseId: String = "",
    val dayId: String = "",
    val dayLabel: String? = null,
    val weekIndexInPhase: Int = 0,
    val isDeload: Boolean = false,
    val locationId: String? = null,
    val locationName: String? = null,
    val status: String,
    val session: WorkoutDayDto? = null,
    val completedAt: Instant? = null,
    val durationSeconds: Int? = null,
)

// ---- Enum parsing with safe fallback ----

private inline fun <reified T : Enum<T>> parseEnum(raw: String?, fallback: T): T =
    raw?.let { value -> enumValues<T>().firstOrNull { it.name.equals(value, ignoreCase = true) } }
        ?: fallback

// ---- Mappers (DTO → domain). The read surface needs no toDto direction;
// ---- LoggedSet maps both ways because the session logger builds the
// ---- completion-request wire shape from domain sets (ADR-0012). ----

fun IntensityDto.toDomain(): Intensity =
    Intensity(kind = parseEnum(kind, IntensityKind.NONE), value = value)

fun DeloadModifierDto.toDomain(): DeloadModifier =
    DeloadModifier(setsMultiplier = setsMultiplier, intensityDelta = intensityDelta)

fun DemoFrameDto.toDomain(): DemoFrame =
    DemoFrame(phase = phase, imageUrl = imageUrl ?: imageCandidates.firstOrNull())

fun ExerciseSummaryDto.toDomain(): ExerciseSummary = ExerciseSummary(
    exerciseId = exerciseId,
    name = name,
    primaryMuscles = primaryMuscles,
    formCues = formCues,
    demoFrames = demoFrames.map { it.toDomain() },
)

fun LoggedSetDto.toDomain(): LoggedSet = LoggedSet(
    weightLbs = weightLbs,
    reps = reps,
    rpe = rpe,
    restSeconds = restSeconds,
    completedAt = completedAt,
)

fun LoggedSet.toDto(): LoggedSetDto = LoggedSetDto(
    weightLbs = weightLbs,
    reps = reps,
    rpe = rpe,
    restSeconds = restSeconds,
    completedAt = completedAt,
)

fun PrescriptionDto.toDomain(): Prescription = Prescription(
    exerciseId = exerciseId,
    orderIndex = orderIndex,
    sets = sets,
    repsMin = repsMin,
    repsMax = repsMax,
    durationSeconds = durationSeconds,
    intensity = intensity?.toDomain(),
    restSeconds = restSeconds,
    tempo = tempo,
    notes = notes,
    deloadModifier = deloadModifier?.toDomain(),
    exercise = exercise?.toDomain(),
    loggedSets = loggedSets.orEmpty().map { it.toDomain() },
)

fun BlockDto.toDomain(): Block = Block(
    blockId = blockId,
    type = parseEnum(type, BlockType.MAIN),
    title = title.orEmpty(),
    orderIndex = orderIndex,
    prescriptions = prescriptions.map { it.toDomain() },
)

fun WorkoutDayDto.toDomain(): WorkoutDay = WorkoutDay(
    dayId = dayId,
    label = label.orEmpty(),
    dayOfWeek = dayOfWeek,
    locationId = locationId.orEmpty(),
    locationName = locationName,
    orderIndex = orderIndex,
    blocks = blocks.map { it.toDomain() },
)

fun PhaseDto.toDomain(): ProgramPhase = ProgramPhase(
    phaseId = phaseId,
    title = title.orEmpty(),
    focus = focus,
    orderIndex = orderIndex,
    status = parseEnum(status, ProgramPhaseStatus.LOCKED),
    weeks = weeks,
    deloadWeekIndex = deloadWeekIndex,
    targetStartDate = targetStartDate,
    targetEndDate = targetEndDate,
    days = days.map { it.toDomain() },
)

fun WorkoutProgramDto.toDomain(): WorkoutProgram = WorkoutProgram(
    programId = programId,
    title = title.orEmpty(),
    description = description,
    goalId = goalId,
    status = parseEnum(status, ProgramStatus.DRAFT),
    source = parseEnum(source, ProgramSource.MANUAL),
    startDate = startDate,
    trainingDays = trainingDays,
    createdAt = createdAt,
    updatedAt = updatedAt,
    totalWeeks = totalWeeks,
    phaseCount = phaseCount,
    completedPhaseCount = completedPhaseCount,
)

fun WorkoutProgramDeepDto.toDomain(): WorkoutProgram {
    val mappedPhases = phases.map { it.toDomain() }
    return WorkoutProgram(
        programId = programId,
        title = title.orEmpty(),
        description = description,
        goalId = goalId,
        goalTitle = goalTitle,
        status = parseEnum(status, ProgramStatus.DRAFT),
        source = parseEnum(source, ProgramSource.MANUAL),
        startDate = startDate,
        trainingDays = trainingDays,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
        totalWeeks = mappedPhases.sumOf { it.weeks },
        phaseCount = mappedPhases.size,
        completedPhaseCount = mappedPhases.count { it.status == ProgramPhaseStatus.COMPLETED },
        phases = mappedPhases,
    )
}

fun ScheduledWorkoutDto.toDomain(): ScheduledWorkout = ScheduledWorkout(
    scheduledId = scheduledId,
    date = date,
    phaseId = phaseId,
    dayId = dayId,
    dayLabel = dayLabel.orEmpty(),
    weekIndexInPhase = weekIndexInPhase,
    isDeload = isDeload,
    locationId = locationId.orEmpty(),
    locationName = locationName,
    status = parseEnum(status, ScheduledStatus.PLANNED),
    session = session?.toDomain(),
    completedAt = completedAt,
    durationSeconds = durationSeconds,
)
