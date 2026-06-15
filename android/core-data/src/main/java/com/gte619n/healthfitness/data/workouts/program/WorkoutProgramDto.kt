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
import com.gte619n.healthfitness.domain.workouts.program.NutritionGuidance
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

/**
 * IMPL-STAB G4 — metadata-only program edit body (PATCH). Maps onto the
 * backend's UpdateProgramRequest; only [title]/[description] are sent so the
 * server leaves everything else (phases, schedule, status) unchanged.
 */
data class UpdateProgramDetailsRequest(
    val title: String,
    val description: String?,
)

data class IntensityDto(
    val kind: String,
    val value: Double? = null,
)

data class DeloadModifierDto(
    val setsMultiplier: Double? = null,
    val intensityDelta: Double? = null,
)

/** IMPL-18: per-phase / program-level calorie + macro guidance (display-only). */
data class NutritionGuidanceDto(
    val kcal: Int? = null,
    val proteinG: Int? = null,
    val carbsG: Int? = null,
    val fatG: Int? = null,
    val note: String? = null,
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
    // IMPL-18: concrete prescribed load + its "why" basis (additive, nullable).
    val targetWeightLbs: Double? = null,
    val loadBasis: String? = null,
)

data class BlockDto(
    // Nullable: a pre-commit chat PROPOSAL has no ids yet (the backend assigns
    // them on commit), so the wire carries `"blockId": null`. A non-null field
    // makes Moshi throw and silently drops the whole proposal.
    val blockId: String? = null,
    val type: String,
    // Nullable: Spring MVC serializes explicit nulls, so the wire may carry
    // `"title": null` (and likewise for the other defaulted strings below).
    // A non-null Kotlin field would make Moshi throw and fail the whole decode.
    val title: String? = null,
    val orderIndex: Int = 0,
    val prescriptions: List<PrescriptionDto> = emptyList(),
)

data class WorkoutDayDto(
    // Nullable for the same reason as BlockDto.blockId (pre-commit proposals).
    val dayId: String? = null,
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
    // Nullable for the same reason as BlockDto.blockId (pre-commit proposals).
    val phaseId: String? = null,
    val title: String? = null,
    val focus: String? = null,
    val orderIndex: Int = 0,
    val status: String,
    val weeks: Int = 0,
    val deloadWeekIndex: Int? = null,
    val targetStartDate: LocalDate? = null,
    val targetEndDate: LocalDate? = null,
    val days: List<WorkoutDayDto> = emptyList(),
    // IMPL-18: per-phase nutrition guidance (additive, nullable).
    val nutritionGuidance: NutritionGuidanceDto? = null,
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
    // Nullable: a not-yet-committed proposal (the `proposal` SSE `program`) has no
    // id — the backend serializes `programId: null`. Committed reads always set it.
    val programId: String? = null,
    val title: String? = null,
    val description: String? = null,
    val goalId: String? = null,
    val goalTitle: String? = null,
    val status: String,
    val source: String,
    val startDate: LocalDate? = null,
    val trainingDays: List<DayOfWeek> = emptyList(),
    val phases: List<PhaseDto> = emptyList(),
    // Nullable: a not-yet-committed proposal (the `proposal` SSE `program`) has
    // no timestamps — the backend serializes them as null. Committed reads carry
    // them. Defaulted so both shapes decode.
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
    val completedAt: Instant? = null,
    // IMPL-18: program-level nutrition fallback (additive, nullable).
    val nutritionGuidance: NutritionGuidanceDto? = null,
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

fun NutritionGuidanceDto.toDomain(): NutritionGuidance = NutritionGuidance(
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    note = note,
)

fun NutritionGuidance.toDto(): NutritionGuidanceDto = NutritionGuidanceDto(
    kcal = kcal,
    proteinG = proteinG,
    carbsG = carbsG,
    fatG = fatG,
    note = note,
)

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
    targetWeightLbs = targetWeightLbs,
    loadBasis = loadBasis,
)

fun BlockDto.toDomain(): Block = Block(
    blockId = blockId.orEmpty(),
    type = parseEnum(type, BlockType.MAIN),
    title = title.orEmpty(),
    orderIndex = orderIndex,
    prescriptions = prescriptions.map { it.toDomain() },
)

fun WorkoutDayDto.toDomain(): WorkoutDay = WorkoutDay(
    dayId = dayId.orEmpty(),
    label = label.orEmpty(),
    dayOfWeek = dayOfWeek,
    locationId = locationId.orEmpty(),
    locationName = locationName,
    orderIndex = orderIndex,
    blocks = blocks.map { it.toDomain() },
)

fun PhaseDto.toDomain(): ProgramPhase = ProgramPhase(
    phaseId = phaseId.orEmpty(),
    title = title.orEmpty(),
    focus = focus,
    orderIndex = orderIndex,
    status = parseEnum(status, ProgramPhaseStatus.LOCKED),
    weeks = weeks,
    deloadWeekIndex = deloadWeekIndex,
    targetStartDate = targetStartDate,
    targetEndDate = targetEndDate,
    days = days.map { it.toDomain() },
    nutritionGuidance = nutritionGuidance?.toDomain(),
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
        programId = programId.orEmpty(),
        title = title.orEmpty(),
        description = description,
        goalId = goalId,
        goalTitle = goalTitle,
        status = parseEnum(status, ProgramStatus.DRAFT),
        source = parseEnum(source, ProgramSource.MANUAL),
        startDate = startDate,
        trainingDays = trainingDays,
        createdAt = createdAt ?: Instant.EPOCH,
        updatedAt = updatedAt ?: Instant.EPOCH,
        completedAt = completedAt,
        totalWeeks = mappedPhases.sumOf { it.weeks },
        phaseCount = mappedPhases.size,
        completedPhaseCount = mappedPhases.count { it.status == ProgramPhaseStatus.COMPLETED },
        phases = mappedPhases,
        nutritionGuidance = nutritionGuidance?.toDomain(),
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
