package com.gte619n.healthfitness.shared.data

import com.gte619n.healthfitness.shared.domain.nutrition.Macros
import com.gte619n.healthfitness.shared.domain.workouts.program.LoggedSet
import com.gte619n.healthfitness.shared.domain.workouts.program.NutritionGuidance
import com.gte619n.healthfitness.shared.domain.workouts.program.ProgramActivationInvalidException
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutHistoryPage
import com.gte619n.healthfitness.shared.domain.workouts.program.WorkoutProgram
import com.gte619n.healthfitness.shared.presentation.workouts.WorkoutStreakSettings
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * IMPL-IOS-01 Phase 3 (iOS wiring) — online-first [WorkoutProgramRepository] over
 * the EXISTING backend, the same endpoints Android's `WorkoutProgramApi` uses.
 * Follows [HttpMedicationRepository] / [HttpBloodReadingRepository]: a
 * [MutableStateFlow] cache per reactive read that fetches lazily on first observe
 * and on [refresh], so the browse screens are reactive without (yet) reading the
 * offline mirror. Mirror-read assembly is deferred like the nutrition/goals pass
 * (recorded in §"Decisions for review") — online-first is correct, just not
 * cold-start/offline polished.
 *
 * The shared `@Serializable` domain shapes ([WorkoutProgram], [ScheduledWorkout],
 * [WorkoutHistoryPage], [LoggedSet], [NutritionGuidance], [Macros]) match the
 * backend `WorkoutProgram(Deep)Response` / `ScheduledWorkoutResponse` /
 * `WorkoutHistoryPageResponse` JSON 1:1 (field names + nullability, verified
 * against the Android DTOs and the backend DayOfWeek Jackson config — enum values
 * serialize UPPERCASE, matching the KMP enum names), so the Ktor JSON decoder
 * deserializes the responses directly; no separate DTO layer is needed. Request
 * bodies that are a distinct subset are declared as private shims below.
 *
 * NOTE on the calendar/stats date window: the backend resolves "today" / the
 * heatmap window in the caller's zone via the `X-Timezone` header, which the shared
 * Ktor client is expected to attach app-wide (same as every other me-scoped read);
 * this repo does not re-send it per request.
 */
class HttpWorkoutProgramRepository(
    private val client: HttpClient,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) : WorkoutProgramRepository {

    // --- Reactive reads (online-first MutableStateFlow caches) ---------------

    private val programs = MutableStateFlow<List<WorkoutProgram>>(emptyList())
    private var programsLoaded = false

    // Deep program caches keyed by programId; each is its own reactive flow.
    private val deepPrograms = mutableMapOf<String, MutableStateFlow<WorkoutProgram?>>()

    override fun observePrograms(): Flow<List<WorkoutProgram>> = programs.onStart {
        if (!programsLoaded) refresh()
    }

    override fun observeProgram(programId: String): Flow<WorkoutProgram?> {
        val flow = deepFlow(programId)
        return flow.onStart { if (flow.value == null) loadDeep(programId) }
    }

    override fun observeCalendar(
        programId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<ScheduledWorkout>> =
        // A calendar read is parameterised by (from,to); model it as a one-shot
        // fetch flow (the VM wraps it in catch{}). A fresh fetch per subscription
        // keeps it simple and correct — the per-program deep flow drives reactivity.
        MutableStateFlow(emptyList<ScheduledWorkout>()).onStart {
            emit(fetchCalendar(programId, from, to))
        }

    override fun observeAllCompleted(from: LocalDate, to: LocalDate): Flow<List<ScheduledWorkout>> =
        // No dedicated endpoint — the Android impl reads the local mirror. Online,
        // best-effort: a single workout-history scan filtered to the window. Empty
        // on failure (the VM also catch{}es).
        MutableStateFlow(emptyList<ScheduledWorkout>()).onStart {
            emit(runCatching { completedInWindow(from, to) }.getOrDefault(emptyList()))
        }

    override suspend fun completedWorkoutDays(): Set<LocalDate> =
        runCatching {
            val stats: WorkoutStatsDto = client.get("api/me/workout-stats").body()
            stats.heatmap.map { it.date }.toSet()
        }.getOrDefault(emptySet())

    override suspend fun refresh() {
        programs.value = client.get("api/me/workout-programs").body()
        programsLoaded = true
    }

    // --- Mutations / online reads -------------------------------------------

    override suspend fun activate(programId: String): Result<Unit> = runCatching {
        try {
            client.post("api/me/workout-programs/$programId/activate")
                .body<List<ScheduledWorkout>>()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.UnprocessableEntity) {
                throw ProgramActivationInvalidException(parseActivationIssues(e))
            }
            throw e
        }
        refresh()
        deepPrograms[programId]?.let { loadDeep(programId) }
        Unit
    }

    override suspend fun continueProgram(programId: String, scope: String): Result<List<ScheduledWorkout>> =
        runCatching {
            val appended: List<ScheduledWorkout> =
                client.post("api/me/workout-programs/$programId/continue") {
                    setBody(ContinueProgramRequest(scope))
                }.body()
            refresh()
            deepPrograms[programId]?.let { loadDeep(programId) }
            appended
        }

    override suspend fun updateDetails(
        programId: String,
        title: String,
        description: String?,
    ): Result<WorkoutProgram> = runCatching {
        val updated: WorkoutProgram = client.patch("api/me/workout-programs/$programId") {
            setBody(UpdateProgramDetailsRequest(title, description))
        }.body()
        deepFlow(programId).value = updated
        refresh()
        updated
    }

    override suspend fun nutritionGuidance(programId: String): Result<NutritionGuidance?> =
        runCatching {
            // 204 → empty body → null. Read as text so an empty body doesn't throw.
            val text = client.get("api/me/workout-programs/$programId/nutrition-guidance").bodyAsText()
            if (text.isBlank()) null else json.decodeFromString(NutritionGuidance.serializer(), text)
        }

    override suspend fun applyNutritionTarget(programId: String): Result<Macros> = runCatching {
        client.post("api/me/workout-programs/$programId/nutrition-target").body()
    }

    override suspend fun runDayToday(programId: String, phaseId: String, dayId: String): Result<String> =
        runCatching {
            val session: ScheduledWorkout =
                client.post("api/me/workout-programs/$programId/sessions") {
                    setBody(RunDayRequest(phaseId = phaseId, dayId = dayId, date = null))
                }.body()
            session.scheduledId
        }

    override suspend fun workoutHistoryPage(page: Int, pageSize: Int): Result<WorkoutHistoryPage> =
        runCatching {
            val result: WorkoutHistoryPage = client.get("api/me/workout-history") {
                parameter("page", page)
                parameter("size", pageSize)
            }.body()
            if (page == 0) cachedFirstPage = result
            result
        }

    private var cachedFirstPage: WorkoutHistoryPage? = null

    override fun cachedFirstHistoryPage(): WorkoutHistoryPage? = cachedFirstPage

    override suspend fun lastSetsFor(programId: String, exerciseId: String): Result<List<LoggedSet>> =
        runCatching {
            // Resilient variant (works for ad-hoc sessions not yet server-persisted):
            // POST the exercise id and pull its list out of the keyed map.
            val map: Map<String, List<LoggedSet>> =
                client.post("api/me/workout-programs/$programId/last-sets") {
                    setBody(LastSetsRequest(listOf(exerciseId)))
                }.body()
            map[exerciseId].orEmpty()
        }

    override suspend fun exerciseHistory(exerciseId: String, limit: Int): Result<List<ExerciseHistoryEntry>> =
        runCatching {
            client.get("api/me/workout-programs/exercises/$exerciseId/history") {
                parameter("limit", limit)
            }.body()
        }

    // --- Internals -----------------------------------------------------------

    private fun deepFlow(programId: String): MutableStateFlow<WorkoutProgram?> =
        deepPrograms.getOrPut(programId) { MutableStateFlow(null) }

    private suspend fun loadDeep(programId: String) {
        val deep: WorkoutProgram = client.get("api/me/workout-programs/$programId").body()
        deepFlow(programId).value = deep
    }

    private suspend fun fetchCalendar(
        programId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<ScheduledWorkout> =
        client.get("api/me/workout-programs/$programId/calendar") {
            parameter("from", from.toString())
            parameter("to", to.toString())
        }.body()

    /** Best-effort cross-program completed-session scan over the history pages. */
    private suspend fun completedInWindow(from: LocalDate, to: LocalDate): List<ScheduledWorkout> {
        val out = mutableListOf<ScheduledWorkout>()
        var page = 0
        while (page < MAX_HISTORY_SCAN_PAGES) {
            val result: WorkoutHistoryPage = client.get("api/me/workout-history") {
                parameter("page", page)
                parameter("size", HISTORY_SCAN_PAGE_SIZE)
            }.body()
            out += result.items.filter { it.date in from..to }
            // Rows are newest-first; once the oldest on this page precedes [from],
            // further pages are all older — stop scanning.
            val oldestOnPage = result.items.minByOrNull { it.date }?.date
            if (!result.hasMore || (oldestOnPage != null && oldestOnPage < from)) break
            page++
        }
        return out
    }

    private suspend fun parseActivationIssues(e: ClientRequestException): List<String> =
        runCatching {
            val body = runCatching { e.response.bodyAsText() }.getOrNull().orEmpty()
            if (body.isBlank()) emptyList()
            else json.decodeFromString(ActivationIssuesDto.serializer(), body).issues
        }.getOrDefault(emptyList()).ifEmpty { listOf("This program can't be activated yet.") }

    @Serializable
    private data class ContinueProgramRequest(val scope: String)

    @Serializable
    private data class UpdateProgramDetailsRequest(val title: String, val description: String?)

    @Serializable
    private data class RunDayRequest(val phaseId: String, val dayId: String, val date: String?)

    @Serializable
    private data class LastSetsRequest(val exerciseIds: List<String>)

    @Serializable
    private data class ActivationIssuesDto(val issues: List<String> = emptyList())

    private companion object {
        const val HISTORY_SCAN_PAGE_SIZE = 50
        const val MAX_HISTORY_SCAN_PAGES = 20
    }
}

/**
 * IMPL-IOS-01 Phase 3 (iOS wiring) — online-first [WorkoutStreakSettingsRepository]
 * over `GET api/me/workout-programs/settings` (Android `WorkoutSettingsApi`). Only
 * the weekly streak target is consumed by the landing. [MutableStateFlow] cache,
 * filled lazily + on [refresh] like the sibling repos.
 */
class HttpWorkoutStreakSettingsRepository(
    private val client: HttpClient,
) : WorkoutStreakSettingsRepository {

    private val target = MutableStateFlow(WorkoutStreakSettings.DEFAULT_WEEKLY_TARGET)
    private var loaded = false

    override val weeklyStreakTarget: Flow<Int> = target.map { it }

    override suspend fun refresh() {
        runCatching {
            val dto: WorkoutSettingsDto = client.get("api/me/workout-programs/settings").body()
            dto.weeklyStreakTarget?.let { target.value = it }
            loaded = true
        }
    }

    @Serializable
    private data class WorkoutSettingsDto(
        val weeklyStreakTarget: Int? = null,
        val preferences: String? = null,
    )
}
