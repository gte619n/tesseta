package com.gte619n.healthfitness.domain.workouts.program

import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.time.Instant
import java.time.LocalDate

// Workout-program domain models (IMPL-AND-15, read-only). These mirror the
// IMPL-15 backend read contract (WorkoutProgramResponse shallow,
// WorkoutProgramDeepResponse, PhaseResponse, WorkoutDayResponse, BlockResponse,
// PrescriptionResponse, ScheduledWorkoutResponse) — adapted to typed
// LocalDate/Instant/DayOfWeek via the core-data Moshi adapters. No proposal /
// chat / logging types: the phone is a viewer in v1.

enum class ProgramStatus { DRAFT, ACTIVE, COMPLETED, ARCHIVED }

enum class ProgramSource { MANUAL, AI_GENERATED, AI_ASSISTED }

enum class ProgramPhaseStatus { LOCKED, ACTIVE, COMPLETED }

enum class BlockType { WARMUP, MOBILITY, CARDIO, MAIN, ACCESSORY, CORE, COOLDOWN, STRETCH }

enum class IntensityKind { RPE, PERCENT_1RM, NONE }

enum class ScheduledStatus { PLANNED, COMPLETED, SKIPPED }

data class Intensity(val kind: IntensityKind, val value: Double?)

data class DeloadModifier(val setsMultiplier: Double?, val intensityDelta: Double?)

/** A single START/MID/END demo still for an exercise (IMPL-14). */
data class DemoFrame(val phase: String, val imageUrl: String?)

/**
 * One set as actually performed in a completed/imported session — the logged
 * counterpart to the planned [Prescription]. [weightLbs] is the load lifted
 * (0 for bodyweight); [reps] is the count, nullable when the source only
 * tracked weight (e.g. imported history).
 */
data class LoggedSet(val weightLbs: Double?, val reps: Int?)

/** Compact, embedded exercise info for rendering a prescription + its demo. */
data class ExerciseSummary(
    val exerciseId: String,
    val name: String,
    val primaryMuscles: List<String>,
    val formCues: List<String>,
    val demoFrames: List<DemoFrame>,
)

data class Prescription(
    val exerciseId: String,
    val orderIndex: Int,
    val sets: Int?,
    val repsMin: Int?,
    val repsMax: Int?,
    val durationSeconds: Int?,
    val intensity: Intensity?,
    val restSeconds: Int?,
    val tempo: String?,
    val notes: String?,
    val deloadModifier: DeloadModifier?,
    /** Actual sets performed; populated only for completed/imported sessions. */
    val loggedSets: List<LoggedSet> = emptyList(),
    /** Embedded by the backend; null only if the backend omits it. */
    val exercise: ExerciseSummary?,
)

data class Block(
    val blockId: String,
    val type: BlockType,
    val title: String,
    val orderIndex: Int,
    val prescriptions: List<Prescription>,
)

data class WorkoutDay(
    val dayId: String,
    val label: String,
    val dayOfWeek: DayOfWeek,
    val locationId: String,
    /** Resolved by the backend for display; may be null. */
    val locationName: String?,
    val orderIndex: Int,
    val blocks: List<Block>,
)

data class ProgramPhase(
    val phaseId: String,
    val title: String,
    val focus: String?,
    val orderIndex: Int,
    val status: ProgramPhaseStatus,
    val weeks: Int,
    val deloadWeekIndex: Int?,
    val targetStartDate: LocalDate?,
    val targetEndDate: LocalDate?,
    /** Empty in the shallow list response. */
    val days: List<WorkoutDay> = emptyList(),
)

data class WorkoutProgram(
    val programId: String,
    val title: String,
    val description: String?,
    val goalId: String?,
    /** Present on the deep response (so the detail can label the goal link). */
    val goalTitle: String? = null,
    val status: ProgramStatus,
    val source: ProgramSource,
    val startDate: LocalDate?,
    val trainingDays: List<DayOfWeek>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val completedAt: Instant? = null,
    // Backend-supplied roll-ups on the shallow list (no client computation).
    val totalWeeks: Int = 0,
    val phaseCount: Int = 0,
    val completedPhaseCount: Int = 0,
    /** Empty in the shallow list; populated on the deep response. */
    val phases: List<ProgramPhase> = emptyList(),
) {
    /** "completed of total" phase pair for progress rendering. */
    val phaseProgress: Pair<Int, Int>
        get() = if (phases.isNotEmpty()) {
            phases.count { it.status == ProgramPhaseStatus.COMPLETED } to phases.size
        } else {
            completedPhaseCount to phaseCount
        }
}

data class ScheduledWorkout(
    val scheduledId: String,
    val date: LocalDate,
    val phaseId: String,
    val dayId: String,
    val dayLabel: String,
    val weekIndexInPhase: Int,
    val isDeload: Boolean,
    val locationId: String,
    val locationName: String?,
    val status: ScheduledStatus,
    /** A full day object (same shape as a deep day); present on the calendar. */
    val session: WorkoutDay? = null,
)

/**
 * Read-only repository for workout programs. Mirrors the IMPL-15 read surface;
 * no mutation, no chat, no logging in v1.
 */
interface WorkoutProgramRepository {
    /** Shallow list of the signed-in user's programs. */
    suspend fun list(): Result<List<WorkoutProgram>>

    /** Deep program (phases → days → blocks → prescriptions + embedded summaries). */
    suspend fun get(programId: String): Result<WorkoutProgram>

    /** Scheduled sessions in the inclusive date range, for the "this week" strip. */
    suspend fun calendar(
        programId: String,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<ScheduledWorkout>>
}
