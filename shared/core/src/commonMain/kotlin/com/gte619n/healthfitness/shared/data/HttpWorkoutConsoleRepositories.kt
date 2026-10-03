package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.goals.Goal
import com.gte619n.healthfitness.shared.domain.goals.GoalStatus
import com.gte619n.healthfitness.shared.domain.workouts.progression.BlockParameters
import com.gte619n.healthfitness.shared.domain.workouts.progression.EnergyBalance
import com.gte619n.healthfitness.shared.domain.workouts.progression.ExerciseStrength
import com.gte619n.healthfitness.shared.domain.workouts.progression.PatternReview
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.Serializable

/**
 * IMPL-IOS-01 Phase 3 (iOS wiring) — online-first repositories for the workout
 * PREFERENCES editor, the PROGRESSION console, and the ad-hoc WORKOUT LIBRARY.
 * These back three previously interface-only contracts
 * ([WorkoutSettingsRepository], [ProgressionRepository] + [WorkoutGoalsRepository],
 * [AdHocLibraryRepository]) so their shared ViewModels can finally be wired on iOS.
 *
 * All online-first over the EXISTING backend — endpoints matched 1:1 to Android's
 * `WorkoutSettingsApi` / `ProgressionApi` / `AdHocWorkoutController`. Same
 * [MutableStateFlow] + lazy-`onStart` shape as [HttpMedicationRepository] /
 * [HttpWorkoutStreakSettingsRepository]: reactive without (yet) reading the offline
 * mirror. Mirror-read assembly is deferred like the nutrition/goals/workouts pass.
 */

// ---------------------------------------------------------------------------
// Workout standing-instructions preferences.
//
// GET/PUT api/me/workout-programs/settings — the SAME endpoint the streak-target
// repo uses, but this one owns the free-text `preferences` field. On PUT the
// backend applies only the non-null field, so sending `preferences` alone never
// clobbers `weeklyStreakTarget` (matches Android's WorkoutSettingsApi).
// ---------------------------------------------------------------------------

class HttpWorkoutSettingsRepository(
    private val client: HttpClient,
) : WorkoutSettingsRepository {

    private val cache = MutableStateFlow("")
    private var loaded = false

    override val preferences: Flow<String> =
        cache
            .onStart { if (!loaded) refresh() }
            .map { it }

    override suspend fun refresh() {
        runCatching {
            val dto: WorkoutSettingsWire = client.get("api/me/workout-programs/settings").body()
            cache.value = dto.preferences ?: ""
            loaded = true
        }
    }

    override suspend fun setPreferences(text: String) {
        val dto: WorkoutSettingsWire =
            client.put("api/me/workout-programs/settings") {
                setBody(WorkoutSettingsWire(preferences = text))
            }.body()
        // Adopt the authoritative echo (backend trims/caps); fall back to what we sent.
        cache.value = dto.preferences ?: text
        loaded = true
    }

    @Serializable
    private data class WorkoutSettingsWire(
        val weeklyStreakTarget: Int? = null,
        val preferences: String? = null,
    )
}

// ---------------------------------------------------------------------------
// Progression console — read-only engine outputs + the mode pin.
//
//   GET api/me/progression/week-review      -> List<PatternReviewWire>
//   GET api/me/progression/block-parameters -> BlockParametersWire
//   PUT api/me/progression/block-parameters -> BlockParametersWire
//   GET api/me/progression/strength         -> List<ExerciseStrengthWire>
//   GET api/me/progression/energy-balance   -> EnergyBalanceWire (may 404 pre-data)
// ---------------------------------------------------------------------------

class HttpProgressionRepository(
    private val client: HttpClient,
) : ProgressionRepository {

    override suspend fun weekReview(): Result<List<PatternReview>> = runCatching {
        val dtos: List<PatternReviewWire> = client.get("api/me/progression/week-review").body()
        dtos.map { it.toDomain() }
    }

    override suspend fun blockParameters(): Result<BlockParameters> = runCatching {
        val dto: BlockParametersWire = client.get("api/me/progression/block-parameters").body()
        dto.toDomain()
    }

    override suspend fun strength(): Result<List<ExerciseStrength>> = runCatching {
        val dtos: List<ExerciseStrengthWire> = client.get("api/me/progression/strength").body()
        dtos.map { it.toDomain() }
    }

    override suspend fun energyBalance(): Result<EnergyBalance?> = runCatching {
        // Additive context — the balance is null until enough intake accrues; a
        // missing/empty payload degrades to null rather than failing the screen.
        runCatching {
            client.get("api/me/progression/energy-balance").body<EnergyBalanceWire>().toDomain()
        }.getOrNull()
    }

    override suspend fun updateBlockParameters(mode: String): Result<BlockParameters> = runCatching {
        val dto: BlockParametersWire =
            client.put("api/me/progression/block-parameters") {
                setBody(UpdateBlockParametersBody(mode = mode))
            }.body()
        dto.toDomain()
    }

    @Serializable
    private data class UpdateBlockParametersBody(
        val mode: String? = null,
        val successCriterion: String? = null,
        val expectedDriftPerDay: Double? = null,
    )

    @Serializable
    private data class PatternReviewWire(
        val pattern: String,
        val trend: String,
        val weeklySlopePct: Double,
        val fatigueIndex: Double,
        val currentTarget: Int,
        val proposedTarget: Int,
        val deload: Boolean,
        val reasoning: String,
    ) {
        fun toDomain() = PatternReview(
            pattern = pattern,
            trend = trend,
            weeklySlopePct = weeklySlopePct,
            fatigueIndex = fatigueIndex,
            currentTarget = currentTarget,
            proposedTarget = proposedTarget,
            deload = deload,
            reasoning = reasoning,
        )
    }

    @Serializable
    private data class BlockParametersWire(
        val mode: String,
        val expectedDriftPerDay: Double,
        val successCriterion: String,
        val manualOverride: Boolean,
        // Wire shape: pattern name -> 2-element [min, max] list.
        val repRanges: Map<String, List<Int>> = emptyMap(),
        val rirCaps: Map<String, Double> = emptyMap(),
        val weeklyCeiling: Map<String, Int> = emptyMap(),
    ) {
        fun toDomain() = BlockParameters(
            mode = mode,
            expectedDriftPerDay = expectedDriftPerDay,
            successCriterion = successCriterion,
            manualOverride = manualOverride,
            // Collapse the [min, max] list into a Pair; tolerate a short list.
            repRanges = repRanges.mapValues { (_, range) ->
                val min = range.getOrNull(0) ?: 0
                val max = range.getOrNull(1) ?: min
                min to max
            },
            rirCaps = rirCaps,
            weeklyCeiling = weeklyCeiling,
        )
    }

    @Serializable
    private data class ExerciseStrengthWire(
        val exerciseId: String,
        val name: String,
        val movementPattern: String? = null,
        val e1rmLbs: Double,
        val confidence: String,
        val observationCount: Int,
    ) {
        fun toDomain() = ExerciseStrength(
            exerciseId = exerciseId,
            name = name,
            movementPattern = movementPattern,
            e1rmLbs = e1rmLbs,
            confidence = confidence,
            observationCount = observationCount,
        )
    }

    @Serializable
    private data class EnergyBalanceWire(
        val maintenanceKcal: Double,
        val meanIntakeKcal: Double,
        val balanceKcal: Double,
        val mode: String,
        val hasIntakeData: Boolean,
    ) {
        fun toDomain() = EnergyBalance(
            maintenanceKcal = maintenanceKcal,
            meanIntakeKcal = meanIntakeKcal,
            balanceKcal = balanceKcal,
            mode = mode,
            hasIntakeData = hasIntakeData,
        )
    }
}

// ---------------------------------------------------------------------------
// Active-goals read for the console's mode <-> goal linkage.
//
// GET api/me/goals -> the same shallow goals list the Goals screen uses; mapped
// to the minimal (id, title, domain) the console needs. Best-effort: empty list
// on any failure (the VM already treats goals as additive, degrade-quiet context).
// ---------------------------------------------------------------------------

class HttpWorkoutGoalsRepository(
    private val client: HttpClient,
) : WorkoutGoalsRepository {

    override suspend fun activeGoals(): List<GoalRef> = runCatching {
        // Same shallow list the Goals screen reads; the backend filters nothing by
        // query, so keep the ACTIVE filter client-side (parity with HttpGoalsRepository).
        val goals: List<Goal> = client.get("api/me/goals").body()
        goals
            .filter { it.status == GoalStatus.ACTIVE }
            .map { GoalRef(goalId = it.goalId, title = it.title, domain = it.domain.name) }
    }.getOrElse { emptyList() }
}

// ---------------------------------------------------------------------------
// Ad-hoc workout LIBRARY — read-only list (IMPL-ADHOC-01).
//
// Android reads this purely from the `adhocWorkouts` mirror; iOS has no mirror
// read for it yet, so this is online-first over GET api/me/adhoc-workouts (the
// shallow summary list), emitted through a MutableStateFlow + lazy onStart so the
// reactive `observeLibrary()` contract holds. Archived items are excluded by the
// backend default; the shared VM also filters `archived`.
// ---------------------------------------------------------------------------

class HttpAdHocLibraryRepository(
    private val client: HttpClient,
) : AdHocLibraryRepository {

    private val cache = MutableStateFlow<List<AdHocLibraryItem>>(emptyList())
    private var loaded = false

    override fun observeLibrary(): Flow<List<AdHocLibraryItem>> =
        cache
            .onStart { if (!loaded) refresh() }
            .map { it }

    private suspend fun refresh() {
        runCatching {
            val dtos: List<AdHocSummaryWire> = client.get("api/me/adhoc-workouts").body()
            cache.value = dtos.mapNotNull { it.toDomain() }
            loaded = true
        }
    }

    @Serializable
    private data class AdHocSummaryWire(
        val adhocId: String? = null,
        val title: String? = null,
        val summary: String? = null,
        val tags: List<String>? = null,
        val pinned: Boolean? = null,
        val runCount: Int? = null,
    ) {
        fun toDomain(): AdHocLibraryItem? {
            val id = adhocId ?: return null
            return AdHocLibraryItem(
                id = id,
                title = title ?: "Workout",
                summary = summary,
                // The shallow summary carries no prompt/exercise-count (D7 list speed).
                purpose = null,
                exerciseCount = 0,
                archived = false,
            )
        }
    }
}
