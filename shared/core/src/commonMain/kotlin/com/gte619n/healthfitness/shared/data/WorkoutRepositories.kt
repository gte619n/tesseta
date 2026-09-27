package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.shared.domain.workouts.program.NutritionGuidance
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutHistoryPage
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.shared.domain.workouts.session.ParkedCompletion
import com.gte619n.healthfitness.shared.domain.workouts.session.PrescriptionKey
import com.gte619n.healthfitness.shared.domain.workouts.session.WorkoutSessionDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 Wave D — the WHOLE workouts vertical's repository surface,
 * declared in ONE file so the three Wave-D agents (this one = hub/programs/
 * history/library reading; the live-session agent; the designer/gyms agent) share
 * a single contract and don't collide.
 *
 * These are the KMP ports of the Android `data.workouts.*` contracts. Each
 * `observe*()` is a reactive read over the Room mirror (offline-first — emits the
 * last-synced data instantly, updates as optimistic writes + sync deltas land);
 * mutating/paged calls return `Result<…>` and are best-effort/online where noted.
 * The concrete implementations (Room-KMP DAO reads + Ktor writes through the
 * outbox) are the remaining Phase 1C body — the ViewModels depend only on these
 * interfaces, so a vertical can be authored + unit-tested with fakes first.
 *
 * OWNERSHIP MAP (so siblings know what is theirs to implement/back):
 *   - [WorkoutProgramRepository]     — this agent (browse) + designer agent (CRUD)
 *   - [AdHocLibraryRepository]       — this agent (library list)
 *   - [WorkoutStreakSettingsRepository] — this agent (streak target for the landing)
 *   - [WorkoutSessionRepository]     — LIVE-SESSION agent (this agent only reads
 *                                       drafts/parked for the resume/recovery banners)
 *   - [ProgressionConsoleRepository] — designer/progression agent
 *   - [GymRepository]                — designer/gyms agent
 */

// ---------------------------------------------------------------------------
// Programs — browse (this agent) + CRUD (designer agent)
// ---------------------------------------------------------------------------

interface WorkoutProgramRepository {
    /** Shallow programs list, reactive off the mirror. */
    fun observePrograms(): Flow<List<WorkoutProgram>>

    /** The deep program tree (phases/days/blocks/prescriptions) for one id; null until mirrored. */
    fun observeProgram(programId: String): Flow<WorkoutProgram?>

    /** Materialized scheduled sessions in [from]..[to] for a program's calendar/strip. */
    fun observeCalendar(programId: String, from: LocalDate, to: LocalDate): Flow<List<ScheduledWorkout>>

    /**
     * COMPLETED sessions across ALL programs in [from]..[to] — the cross-program
     * streak source (so a new program keeps a live weekly streak).
     */
    fun observeAllCompleted(from: LocalDate, to: LocalDate): Flow<List<ScheduledWorkout>>

    /**
     * Days (cross-program, INCLUDING archived programs) the user completed a
     * workout, over the server's ~6-month heatmap window, from the same
     * `/api/me/workout-stats` read that backs the web consistency heatmap. This is
     * the ONLY source that includes archived programs' sessions — the per-program
     * calendar and [observeAllCompleted] both miss them — so it lets the compliance
     * calendar show an earlier program's months (parity with web). Online/
     * best-effort: returns empty on failure/kill-switch so the Room-backed grid
     * still renders offline. Dates are already in the caller's zone (X-Timezone).
     * (Android WorkoutProgramRepository.completedWorkoutDays, #279/#280.)
     */
    suspend fun completedWorkoutDays(): Set<LocalDate>

    /** Best-effort revalidation; never flips a screen back to Loading. */
    suspend fun refresh()

    /** Activate (materialize sessions + mark ACTIVE). 422 → ProgramActivationInvalidException. */
    suspend fun activate(programId: String): Result<Unit>

    /** PATCH title/description (designer agent may extend); returns the updated program. */
    suspend fun updateDetails(programId: String, title: String, description: String?): Result<WorkoutProgram>

    /** Best-effort per-program nutrition guidance (drives the "Apply nutrition" action). */
    suspend fun nutritionGuidance(programId: String): Result<NutritionGuidance?>

    /** Apply the program's nutrition guidance as the daily macro target; returns what was applied. */
    suspend fun applyNutritionTarget(programId: String): Result<Macros>

    /** Materialize one workout day as a session dated today; returns the scheduledId. */
    suspend fun runDayToday(programId: String, phaseId: String, dayId: String): Result<String>

    // --- Workout History (read-only, paged, online) ---

    /** One page of COMPLETED sessions (newest first) across all programs. */
    suspend fun workoutHistoryPage(page: Int, pageSize: Int): Result<WorkoutHistoryPage>

    /** The first history page if already cached this session (ADR-0018 no-spinner re-entry), else null. */
    fun cachedFirstHistoryPage(): WorkoutHistoryPage?

    // --- Prior performance (last-sets) for the detail prefill ---

    /**
     * The most recent logged sets for an exercise (by [exerciseId]) within a
     * program — powers WorkoutDetail's "last time you did…" prior-performance
     * prefill. Empty when nothing was ever logged (or the session isn't
     * server-persisted yet — see the last-sets-404 memo). Online, best-effort.
     */
    suspend fun lastSetsFor(programId: String, exerciseId: String): Result<List<LoggedSet>>
}

/**
 * Cross-program workout stats (the web Overview read-model, `GET /api/me/workout-stats`).
 * Only [heatmap] is consumed — the completed-day list including archived programs
 * — so the rest of the bundle (streak/series/PRs) is intentionally not modelled.
 * The concrete Ktor repo maps [heatmap] to the `Set<LocalDate>` its
 * [WorkoutProgramRepository.completedWorkoutDays] returns. (Android WorkoutStatsDto.)
 */
@Serializable
data class WorkoutStatsDto(val heatmap: List<HeatmapDayDto> = emptyList())

/** One completed-workout day in the consistency heatmap. */
@Serializable
data class HeatmapDayDto(val date: LocalDate, val sessionCount: Int = 0)

// ---------------------------------------------------------------------------
// Ad-hoc workout library (IMPL-ADHOC-01) — this agent (list)
// ---------------------------------------------------------------------------

/**
 * A single AI-generated, purpose-driven ad-hoc workout in the reusable library
 * (IMPL-ADHOC-01). The full designer/runner types belong to a later wave; the
 * browse list only needs these display fields, so a compact shared contract type
 * is declared here rather than pulling the whole domain in.
 */
@Serializable
data class AdHocLibraryItem(
    val id: String,
    val title: String,
    val summary: String? = null,
    /** Free-text purpose/prompt the workout was generated for. */
    val purpose: String? = null,
    /** Prescribed-exercise count for the "N exercises" hint. */
    val exerciseCount: Int = 0,
    /** True once archived (kept out of the active list). */
    val archived: Boolean = false,
)

interface AdHocLibraryRepository {
    /** Reactive, offline-first library list off the `adhocWorkouts` mirror. */
    fun observeLibrary(): Flow<List<AdHocLibraryItem>>
}

// ---------------------------------------------------------------------------
// Workout streak settings — this agent (weekly streak target for the landing).
//
// NB: named `WorkoutStreakSettingsRepository` (not `WorkoutSettingsRepository`)
// because the Wave-A `SettingsRepositories.kt` already declares a DIFFERENT
// `WorkoutSettingsRepository` (standing-instructions `preferences`/`setPreferences`).
// Distinct name = no duplicate-declaration collision; this one is strictly the
// streak-target cache the "This Week" landing recomputes its streak against.
// ---------------------------------------------------------------------------

interface WorkoutStreakSettingsRepository {
    /** Weekly completed-workout target that keeps the streak alive; reactive off the cache. */
    val weeklyStreakTarget: Flow<Int>

    /** Sync the streak target from the backend into the local cache. */
    suspend fun refresh()
}

// ---------------------------------------------------------------------------
// Live session — LIVE-SESSION agent owns the writes; this agent only reads
// drafts/parked completions for the Resume / recovery banners.
// ---------------------------------------------------------------------------

interface WorkoutSessionRepository {
    /** In-progress local drafts (ADR-0012); this agent filters by programId for the Resume banner. */
    fun observeDrafts(): Flow<List<WorkoutSessionDraft>>

    /** Parked completion uploads the server terminally rejected (IMPL-17 A10) — recovery banner. */
    fun observeParkedCompletions(): Flow<List<ParkedCompletion>>

    /** Revert a logged session to PLANNED (clears actuals server-side). */
    suspend fun reset(programId: String, scheduledId: String): Result<Unit>

    /** Re-materialize a parked completion as a draft to re-finish. */
    suspend fun restoreParked(programId: String, scheduledId: String): Result<Unit>

    /** Give up on a parked completion (when the session is gone). */
    suspend fun discardParked(programId: String, scheduledId: String): Result<Unit>

    // --- Live session (Wave D-ii, merged by integrator per option A) ---------
    // Ported from android/core-data/.../data/workouts/session/WorkoutSessionRepository.
    // Every set edit goes straight to the encrypted Room-KMP draft (ADR-0012 —
    // survives process death, offline); finish/skip route one idempotent
    // completion upsert through the outbox. The countdown timer is deliberately
    // NOT here (ADR-0012 D6: process-scoped); the shared VM owns it as a
    // self-ticking flow (RestTimerHolder).

    /** Resume an existing draft for (programId, scheduledId) or snapshot a fresh ACTIVE draft. Idempotent. */
    suspend fun start(programId: String, scheduledId: String): Result<Unit>

    /** Cached-only peek — non-null iff a draft already exists (resume vs. cold start). */
    suspend fun peekDraft(programId: String, scheduledId: String): WorkoutSessionDraft?

    /** Reactive Room-mirror read of the live draft; emits null after a terminal action. */
    fun observeDraft(programId: String, scheduledId: String): Flow<WorkoutSessionDraft?>

    /** Persist the full logged-set list for one prescription (offline-first Room write). */
    suspend fun updateSets(
        programId: String,
        scheduledId: String,
        key: PrescriptionKey,
        sets: List<LoggedSet>,
    ): Result<Unit>

    /** What each exercise was performed last time, keyed by exerciseId (most recent COMPLETED session). Best-effort. */
    suspend fun lastSets(programId: String, scheduledId: String): Map<String, List<LoggedSet>>

    /** Re-anchor the draft clock to now when the lifter taps Start. */
    suspend fun markStarted(programId: String, scheduledId: String): Result<Unit>

    /** Upload COMPLETED with all logged actuals through the outbox. [feeling] = optional 1–5. */
    suspend fun finish(programId: String, scheduledId: String, feeling: Int?): Result<Unit>

    /** Best-effort AI recap note fetched after finishing; null when unavailable. */
    suspend fun fetchRecap(programId: String, scheduledId: String): String?

    /** Upload SKIPPED (clears actuals) and remove the draft. */
    suspend fun skip(programId: String, scheduledId: String): Result<Unit>

    /** Throw the draft away locally — nothing reaches the backend. */
    suspend fun discard(programId: String, scheduledId: String): Result<Unit>
}

// ---------------------------------------------------------------------------
// Progression console + gyms/locations — owned by the DESIGNER/GYMS agent
// (Wave D-iii), which declares its own contracts:
//   - `data/WorkoutDesignerGymRepositories.kt`  → LocationRepository /
//        EquipmentRepository / GymScanRepository / ProgressionRepository / …
// This agent (browse) doesn't consume those, so nothing is declared here to
// avoid duplicate-declaration collisions with that sibling file. The hub's
// "Gyms" / "Design" entry points are surfaced by THIS agent's views as
// navigation cases (see WorkoutsRoutes.swift), wired to those repos downstream.
// ---------------------------------------------------------------------------
