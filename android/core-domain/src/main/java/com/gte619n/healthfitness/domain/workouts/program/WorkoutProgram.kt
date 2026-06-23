package com.gte619n.healthfitness.domain.workouts.program

import com.gte619n.healthfitness.domain.common.DayOfWeek
import java.time.Instant
import java.time.LocalDate

// Workout-program domain models (IMPL-AND-15, read-only). These mirror the
// IMPL-15 backend read contract (WorkoutProgramResponse shallow,
// WorkoutProgramDeepResponse, PhaseResponse, WorkoutDayResponse, BlockResponse,
// PrescriptionResponse, ScheduledWorkoutResponse) — adapted to typed
// LocalDate/Instant/DayOfWeek via the core-data Moshi adapters. No proposal /
// chat types. ADR-0012 adds the performed-session actuals ([LoggedSet],
// `ScheduledWorkout.completedAt`/`durationSeconds`); the live-logging draft
// types live in `domain.workouts.session`.

enum class ProgramStatus { DRAFT, ACTIVE, COMPLETED, ARCHIVED }

enum class ProgramSource { MANUAL, AI_GENERATED, AI_ASSISTED }

enum class ProgramPhaseStatus { LOCKED, ACTIVE, COMPLETED }

enum class BlockType { WARMUP, MOBILITY, CARDIO, MAIN, ACCESSORY, CORE, COOLDOWN, STRETCH }

enum class IntensityKind { RPE, PERCENT_1RM, NONE }

enum class ScheduledStatus { PLANNED, COMPLETED, SKIPPED }

data class Intensity(val kind: IntensityKind, val value: Double?)

data class DeloadModifier(val setsMultiplier: Double?, val intensityDelta: Double?)

/**
 * Non-binding per-phase (or program-level fallback) calorie/macro guidance
 * written alongside a designed program (IMPL-18 S3/R4). Display-only — the user
 * still logs food in the nutrition module. All fields nullable.
 */
data class NutritionGuidance(
    val kcal: Int? = null,
    val proteinG: Int? = null,
    val carbsG: Int? = null,
    val fatG: Int? = null,
    val note: String? = null,
) {
    /** True when every field is empty — treat as "no guidance" and omit from the UI. */
    val isEmpty: Boolean
        get() = kcal == null && proteinG == null && carbsG == null && fatG == null &&
            note.isNullOrBlank()
}

/**
 * A single demo still for an exercise. IMPL-19 replaced the fixed
 * START/MID/END triad with a per-exercise frame plan: [key]/[label]/[caption]
 * are denormalized from the plan's `FrameSpec`, [order] drives display order,
 * and [phase] is the deprecated legacy enum (nullable, read-only for old docs).
 */
data class DemoFrame(
    val key: String = "",
    val label: String = "",
    val caption: String = "",
    val order: Int = 0,
    val imageUrl: String?,
    val phase: String? = null,
)

/** Compact, embedded exercise info for rendering a prescription + its demo. */
data class ExerciseSummary(
    val exerciseId: String,
    val name: String,
    val primaryMuscles: List<String>,
    val formCues: List<String>,
    val demoFrames: List<DemoFrame>,
)

/**
 * One performed set's full actuals (ADR-0012 Decision 2 / IMPL-17 D3). Every
 * field is nullable: imported-history rows are weight-only (reps null) and the
 * logger keeps everything beyond weight/reps skippable.
 */
data class LoggedSet(
    val weightLbs: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val restSeconds: Int? = null,
    val completedAt: Instant? = null,
    /** Held time for a timed exercise (stretch/mobility/cardio); the time-based counterpart to [reps]. */
    val durationSeconds: Int? = null,
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
    /** Embedded by the backend; null only if the backend omits it. */
    val exercise: ExerciseSummary?,
    /**
     * Actual sets performed (ADR-0012); populated only for completed/imported
     * sessions, empty until the session is logged.
     */
    val loggedSets: List<LoggedSet> = emptyList(),
    /** IMPL-18: concrete history-grounded prescribed load; null → fall back to [intensity]. */
    val targetWeightLbs: Double? = null,
    /** IMPL-18: short "why" for the prescribed load (e1RM / last done / ramp), shown on tap (R6). */
    val loadBasis: String? = null,
) {
    /**
     * A timed exercise (stretch / mobility / cardio hold) — logged by held time
     * rather than reps. True when a duration is prescribed and no rep target is.
     */
    val isTimed: Boolean
        get() = durationSeconds != null && repsMin == null && repsMax == null
}

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
    /** IMPL-18: per-phase calorie/macro guidance (display-only); null = none. */
    val nutritionGuidance: NutritionGuidance? = null,
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
    /** IMPL-18: program-level nutrition fallback when phases carry none; null = none. */
    val nutritionGuidance: NutritionGuidance? = null,
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
    /** Outcome fields (ADR-0012); set once the session is COMPLETED. */
    val completedAt: Instant? = null,
    val durationSeconds: Int? = null,
    /**
     * Owning program + phase titles, resolved by the Workout History read so the
     * list can draw program/phase delineation headers. Null elsewhere (calendar).
     */
    val programTitle: String? = null,
    val phaseTitle: String? = null,
)

/**
 * One page of Workout History rows (newest first). [hasMore] tells the caller
 * whether a further [page] exists, driving the screen's load-on-scroll.
 */
data class WorkoutHistoryPage(
    val items: List<ScheduledWorkout>,
    val page: Int,
    val total: Int,
    val hasMore: Boolean,
)

/**
 * Activation failed validation (HTTP 422): the backend returned the actionable
 * issue list (same shape as the designer's commit 422). Carried as a typed
 * failure so the UI can surface the specific issues inline instead of a generic
 * "couldn't activate" (IMPL-STAB G1). [issues] is never empty.
 */
class ProgramActivationInvalidException(val issues: List<String>) :
    Exception(issues.joinToString("; "))

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

    /**
     * One page of COMPLETED sessions across all programs, newest first, deep
     * enough to review (each carries its blocks → prescriptions → logged sets).
     * Paged for the history screen's load-on-scroll. Online-only, read-only.
     */
    suspend fun workoutHistoryPage(page: Int, size: Int): Result<WorkoutHistoryPage>

    /**
     * The program's effective nutrition guidance (active phase's, else
     * program-level), or null when it has none. Online-only.
     */
    suspend fun nutritionGuidance(programId: String): Result<NutritionGuidance?>

    /** Apply the program's guidance as the user's macro target; returns the saved macros. Online-only. */
    suspend fun applyNutritionTarget(programId: String): Result<com.gte619n.healthfitness.domain.nutrition.Macros>

    /**
     * Activate a program (materialize sessions + mark ACTIVE) and refresh the
     * local mirror so the detail/list reflect it. Returns the materialized
     * sessions. Online-only.
     */
    suspend fun activate(programId: String): Result<List<ScheduledWorkout>>

    /**
     * Edit a program's metadata (title/description) via PATCH and refresh the
     * mirror so the detail reflects it without waiting for a sync. Structural
     * edits go through the conversational designer, not this. Online-only
     * (IMPL-STAB G4). Returns the updated deep program.
     */
    suspend fun updateDetails(
        programId: String,
        title: String,
        description: String?,
    ): Result<WorkoutProgram>
}
