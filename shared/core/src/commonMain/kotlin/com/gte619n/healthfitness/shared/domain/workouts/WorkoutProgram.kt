package com.gte619n.healthfitness.shared.domain.workouts.program

import com.gte619n.healthfitness.shared.domain.common.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 1A — extracted from
 * android/core-domain/.../domain/workouts/program/WorkoutProgram.kt
 * (D3: Moshi→kotlinx.serialization).
 *
 * `java.time.Instant`/`LocalDate` swapped for kotlinx-datetime; the shared
 * [DayOfWeek] moved with it. Field names and nullability match the Android
 * source 1:1. Pure derived properties (`isTimed`, `phaseProgress`) and the typed
 * failure [ProgramActivationInvalidException] are carried verbatim.
 *
 * Workout-program domain models (IMPL-AND-15, read-only). These mirror the
 * IMPL-15 backend read contract (WorkoutProgramResponse shallow,
 * WorkoutProgramDeepResponse, PhaseResponse, WorkoutDayResponse, BlockResponse,
 * PrescriptionResponse, ScheduledWorkoutResponse). No proposal / chat types.
 * ADR-0012 adds the performed-session actuals ([LoggedSet],
 * `ScheduledWorkout.completedAt`/`durationSeconds`); the live-logging draft
 * types live in `domain.workouts.session`.
 */

enum class ProgramStatus { DRAFT, ACTIVE, COMPLETED, ARCHIVED }

enum class ProgramSource { MANUAL, AI_GENERATED, AI_ASSISTED }

enum class ProgramPhaseStatus { LOCKED, ACTIVE, COMPLETED }

enum class BlockType { WARMUP, MOBILITY, CARDIO, MAIN, ACCESSORY, CORE, COOLDOWN, STRETCH }

enum class IntensityKind { RPE, PERCENT_1RM, NONE }

enum class ScheduledStatus { PLANNED, COMPLETED, SKIPPED }

@Serializable
data class Intensity(val kind: IntensityKind, val value: Double?)

@Serializable
data class DeloadModifier(val setsMultiplier: Double?, val intensityDelta: Double?)

/**
 * Non-binding per-phase (or program-level fallback) calorie/macro guidance
 * written alongside a designed program (IMPL-18 S3/R4). Display-only — the user
 * still logs food in the nutrition module. All fields nullable.
 */
@Serializable
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
@Serializable
data class DemoFrame(
    val key: String = "",
    val label: String = "",
    val caption: String = "",
    val order: Int = 0,
    val imageUrl: String?,
    val phase: String? = null,
)

/** Compact, embedded exercise info for rendering a prescription + its demo. */
@Serializable
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
@Serializable
data class LoggedSet(
    val weightLbs: Double? = null,
    val reps: Int? = null,
    /** Legacy effort scale, kept for back-compat with older payloads / imported history. */
    val rpe: Double? = null,
    /**
     * Reps-in-reserve for this set (the RPE successor). Nullable so old payloads
     * and weight-only imported rows stay valid.
     */
    val rir: Double? = null,
    /** How [rir] was obtained: `REPORTED | INFERRED_TARGET | INFERRED_FAILURE | ABSENT`. Null on legacy rows. */
    val rirSource: String? = null,
    val restSeconds: Int? = null,
    val completedAt: Instant? = null,
    /** Held time for a timed exercise (stretch/mobility/cardio); the time-based counterpart to [reps]. */
    val durationSeconds: Int? = null,
    /**
     * IMPL-FIXPACK-01 Phase 4: the "could I do more/less?" capability signal for a
     * timed exercise's final set — RIR makes no sense for a hold, so this captures
     * whether the hold was too easy / about right / too hard. `LESS | SAME | MORE`
     * (capability: MORE = could have held longer). Nullable: only the final timed
     * set carries it, and rep sets / legacy rows never do. Captured now; the
     * progression engine still skips timed exercises (see SessionLoop), so it does
     * not yet move next-session targets.
     */
    val timedEffort: String? = null,
)

/** Direction the progression engine moved the prescription vs. last time. */
enum class ProgressionDirection { UP, DOWN, HOLD }

/** Confidence the progression engine attaches to its [PrescriptionRationale]. */
enum class ProgressionConfidence { HIGH, MEDIUM, LOW }

/**
 * The progression-engine "why" for a prescription: which [path] fired, the
 * [direction] it moved the load/reps/sets, the concrete deltas, a [confidence]
 * level, and the human-readable [inputs] that fed the decision. Entirely
 * nullable/optional on the wire — absent for un-progressed prescriptions.
 */
@Serializable
data class PrescriptionRationale(
    val path: String?,
    val direction: ProgressionDirection,
    val deltaLbs: Double? = null,
    val deltaReps: Int? = null,
    val deltaSets: Int? = null,
    val confidence: ProgressionConfidence,
    val inputs: List<String> = emptyList(),
)

@Serializable
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
    /** Progression-engine rationale for this prescription; null → no engine decision to show. */
    val rationale: PrescriptionRationale? = null,
    /**
     * IMPL-PROG-02 F6: true only for real bodyweight movements (dips/pull-ups/push-ups).
     * The coach announces/labels "body weight" ONLY when this is true — a weighted lift
     * with no known load (e.g. cable push-downs) must never resolve to "body weight".
     */
    val isBodyweight: Boolean = false,
) {
    /**
     * A timed exercise (stretch / mobility / cardio hold) — logged by held time
     * rather than reps. True when a duration is prescribed and no rep target is.
     */
    val isTimed: Boolean
        get() = durationSeconds != null && repsMin == null && repsMax == null
}

@Serializable
data class Block(
    val blockId: String,
    val type: BlockType,
    val title: String,
    val orderIndex: Int,
    val prescriptions: List<Prescription>,
)

@Serializable
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

@Serializable
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

@Serializable
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

@Serializable
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
    /**
     * Owning program id. Populated by the cross-program Workout History read so a
     * row can be acted on (delete/reset); empty on calendar reads, where the
     * program context is already known.
     */
    val programId: String = "",
)

/**
 * One page of Workout History rows (newest first). [hasMore] tells the caller
 * whether a further [page] exists, driving the screen's load-on-scroll.
 */
@Serializable
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
