package com.gte619n.healthfitness.shared.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.BloodMarkerSummary
import com.gte619n.healthfitness.shared.data.DailyMetricPoint
import com.gte619n.healthfitness.shared.data.DashboardBloodMarkerRepository
import com.gte619n.healthfitness.shared.data.DashboardBodyCompositionRepository
import com.gte619n.healthfitness.shared.data.DashboardDailyMetricsRepository
import com.gte619n.healthfitness.shared.data.DashboardNutritionRepository
import com.gte619n.healthfitness.shared.data.DashboardProfileRepository
import com.gte619n.healthfitness.shared.data.DashboardRecentActivityRepository
import com.gte619n.healthfitness.shared.data.DashboardUser
import com.gte619n.healthfitness.shared.data.DashboardWorkoutRepository
import com.gte619n.healthfitness.shared.data.RecentActivityEntry
import com.gte619n.healthfitness.shared.data.TodayWorkout
import com.gte619n.healthfitness.shared.data.WeightSummary
import com.gte619n.healthfitness.shared.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.shared.domain.workouts.program.ScheduledStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * IMPL-IOS-01 Phase 3 Wave A1 — SHARED dashboard/Today ViewModel. Port of the
 * Android `mobile/dashboard/DashboardViewModel.kt` (+ the home
 * `TodayWorkoutViewModel`'s pick) to KMP `commonMain`, following the reference
 * `presentation/medications/MedicationsViewModel.kt` pattern:
 *
 *  - `androidx.lifecycle.ViewModel` (KMP artifact, D2); repositories are plain
 *    constructor params wired by platform DI (Hilt on Android, a factory on iOS).
 *  - The per-card [CardState] envelope + offline-first loaders are IDENTICAL to
 *    the Android original: each card seeds instantly from a local-only cache on a
 *    cold open (no spinner), then revalidates from the network, and a failed
 *    revalidation KEEPS the cached/Loaded value — [uiState] never flips a
 *    populated card back to Loading (the shared no-Loading-reset invariant).
 *  - The "Today's workout" dashlet is composed here too (Android splits it into a
 *    sibling `TodayWorkoutViewModel`; on iOS it's one screen VM). The in-progress
 *    draft is reactive off the local store; the planned/completed pick is a
 *    best-effort calendar resolve refreshed by [refresh].
 *
 * Android platform-only wiring is intentionally dropped from the shared VM (each
 * platform re-supplies it): `LocalWriteBus`/`SyncSignals` invalidation, the
 * `UnitPreferencesRepository` weight-unit stream (weight formatting is a view
 * concern — the VM exposes `latestLb`, the SwiftUI/Compose layer formats), and
 * `SystemClock` (replaced by a monotonic-safe wall clock for the resume TTL,
 * which is only a best-effort throttle). Field names/logic are otherwise 1:1.
 */

// Per-card state envelope so each dashboard card loads, errors, and retries
// independently (IMPL-AND-01, verbatim).
sealed interface CardState<out T> {
    data object Loading : CardState<Nothing>
    data class Loaded<T>(val data: T) : CardState<T>
    data class Error(val message: String) : CardState<Nothing>
}

data class DashboardUiState(
    val bodyComposition: CardState<WeightSummary?>,
    val dailyMetrics: CardState<List<DailyMetricPoint>>,
    val blood: CardState<List<BloodMarkerSummary>>,
    val nutrition: CardState<NutritionDay>,
    val recentActivity: CardState<List<RecentActivityEntry>>,
    // Today's workout pick (composed from the reactive draft + calendar resolve).
    val todayWorkout: TodayWorkout = TodayWorkout.Hidden,
    val user: DashboardUser? = null,
    // Metric keys hidden from the dashboard (biometrics settings). Cards for
    // these keys are dropped from the vitals grid. Sourced from the profile.
    val hiddenBiometrics: Set<String> = emptySet(),
    // Wall-clock time the dashboard cards were last successfully (re)loaded.
    // Null until the first load lands; drives the header's "Updated …" subtitle.
    val lastUpdated: Instant? = null,
) {
    companion object {
        val initial = DashboardUiState(
            CardState.Loading,
            CardState.Loading,
            CardState.Loading,
            CardState.Loading,
            CardState.Loading,
        )
    }
}

class DashboardViewModel(
    private val bodyComp: DashboardBodyCompositionRepository,
    private val dailyMetrics: DashboardDailyMetricsRepository,
    private val blood: DashboardBloodMarkerRepository,
    private val nutrition: DashboardNutritionRepository,
    private val recent: DashboardRecentActivityRepository,
    private val workouts: DashboardWorkoutRepository,
    private val profile: DashboardProfileRepository,
    private val clock: Clock = Clock.System,
) : ViewModel() {

    private val _ui = MutableStateFlow(DashboardUiState.initial)
    val uiState: StateFlow<DashboardUiState> = _ui.asStateFlow()

    /**
     * The reactive "Today's workout" pick, folded into [uiState]. A genuinely
     * in-progress draft (PLANNED scheduled status) resumes here and wins over the
     * calendar-resolved pick; a draft opened over an already-finished session
     * (review) falls through so it doesn't read as "Resume". The resolved pick
     * (completed-recap / start) is refreshed by [refresh].
     */
    private val todaySession = MutableStateFlow<TodayWorkout?>(null)
    private val workoutState: StateFlow<TodayWorkout> =
        combineDraftsAndResolved()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayWorkout.Hidden)

    private fun combineDraftsAndResolved(): Flow<TodayWorkout> =
        combine(workouts.observeDrafts(), todaySession) { drafts, resolved ->
            val draft = drafts.firstOrNull { it.scheduled.status == ScheduledStatus.PLANNED }
            when {
                draft != null -> TodayWorkout.Resume(
                    programId = draft.programId,
                    scheduledId = draft.scheduledId,
                    label = draft.scheduled.dayLabel.ifBlank { null },
                    setsLogged = draft.totalLoggedSets,
                    startedAt = draft.startedAt,
                )
                resolved != null -> resolved
                else -> TodayWorkout.Hidden
            }
        }

    // Monotonic-ish timestamp (epoch millis) of the last refresh we kicked off.
    // Used to skip the batch of network calls triggered on every resume when the
    // user simply navigates back to the dashboard.
    private var lastRefreshAt: Long = 0L

    init {
        // Mirror the workout pick into the composed UI state as it emits.
        viewModelScope.launch {
            workoutState.collect { pick -> _ui.update { it.copy(todayWorkout = pick) } }
        }
        refresh()
    }

    /**
     * Reloads every dashboard card. On resume this fires on every navigation
     * back, so non-forced calls within [REFRESH_TTL_MS] of the previous refresh
     * are skipped. The first load (lastRefreshAt == 0) and any [force] = true
     * caller (pull-to-refresh / explicit) always go through.
     */
    fun refresh(force: Boolean = false) {
        val now = clock.now().toEpochMilliseconds()
        if (!force && lastRefreshAt != 0L && now - lastRefreshAt < REFRESH_TTL_MS) return
        // Stamp the TTL against the whole batch of primary cards, not a single
        // card: launch them as joinable jobs, await all, then stamp once. Each
        // job returns whether its load succeeded so we can avoid stamping when
        // the entire batch failed (e.g. offline) — that lets the next resume
        // retry instead of being TTL-blocked.
        viewModelScope.launch {
            val results = listOf(
                loadBodyComposition(),
                loadDailyMetrics(),
                loadBlood(),
                loadNutrition(),
                loadRecentActivity(),
            ).awaitAll()
            if (results.any { it }) {
                lastRefreshAt = clock.now().toEpochMilliseconds()
                _ui.update { it.copy(lastUpdated = clock.now()) }
            }
        }
        loadUser()
        loadTodayWorkout()
    }

    fun retryBodyComposition() { loadBodyComposition() }
    fun retryDailyMetrics() { loadDailyMetrics() }
    fun retryBlood() { loadBlood() }
    fun retryNutrition() { loadNutrition() }
    fun retryRecentActivity() { loadRecentActivity() }

    // Each loader returns a Deferred<Boolean> — true on success — so refresh()
    // can settle the whole batch before deciding whether to stamp the TTL.
    // Retry methods ignore the result; they are never TTL-gated.
    //
    // offline-fix — cache-first, revalidate in the background: a loader first seeds
    // the card from the repository's local-only cache when it has no data yet, so a
    // cold open shows the last-synced value INSTANTLY with no spinner, then it
    // revalidates from the network. A network failure keeps the cached (or already-
    // Loaded) value on screen and only surfaces an Error when there was nothing to
    // show — so the only blocking loader left is the genuine first sync (no cache yet).
    private fun loadBodyComposition() = viewModelScope.async {
        if (_ui.value.bodyComposition !is CardState.Loaded) {
            runCatching { bodyComp.cachedRecent() }.getOrNull()?.let { c ->
                if (_ui.value.bodyComposition !is CardState.Loaded) {
                    _ui.update { it.copy(bodyComposition = CardState.Loaded(c)) }
                }
            }
        }
        runCatching { bodyComp.loadRecent() }
            .onSuccess { d -> _ui.update { it.copy(bodyComposition = CardState.Loaded(d)) } }
            .onFailure {
                if (_ui.value.bodyComposition !is CardState.Loaded) {
                    _ui.update { it.copy(bodyComposition = CardState.Error("Couldn't load weight")) }
                }
            }
            .isSuccess
    }

    private fun loadDailyMetrics() = viewModelScope.async {
        if (_ui.value.dailyMetrics !is CardState.Loaded) {
            val cached = runCatching { dailyMetrics.cachedRecentMetrics() }.getOrNull()
            if (!cached.isNullOrEmpty() && _ui.value.dailyMetrics !is CardState.Loaded) {
                _ui.update { it.copy(dailyMetrics = CardState.Loaded(cached)) }
            }
        }
        runCatching { dailyMetrics.loadRecentMetrics() }
            .onSuccess { d -> _ui.update { it.copy(dailyMetrics = CardState.Loaded(d)) } }
            .onFailure {
                if (_ui.value.dailyMetrics !is CardState.Loaded) {
                    _ui.update { it.copy(dailyMetrics = CardState.Error("Couldn't load metrics")) }
                }
            }
            .isSuccess
    }

    private fun loadBlood() = viewModelScope.async {
        if (_ui.value.blood !is CardState.Loaded) {
            val cached = runCatching { blood.cachedDashboardMarkers() }.getOrNull()
            if (!cached.isNullOrEmpty() && _ui.value.blood !is CardState.Loaded) {
                _ui.update { it.copy(blood = CardState.Loaded(cached)) }
            }
        }
        runCatching { blood.loadDashboardMarkers() }
            .onSuccess { d -> _ui.update { it.copy(blood = CardState.Loaded(d)) } }
            .onFailure {
                if (_ui.value.blood !is CardState.Loaded) {
                    _ui.update { it.copy(blood = CardState.Error("Couldn't load blood")) }
                }
            }
            .isSuccess
    }

    private fun loadNutrition() = viewModelScope.async {
        if (_ui.value.nutrition !is CardState.Loaded) {
            runCatching { nutrition.cachedToday() }.getOrNull()?.let { c ->
                if (_ui.value.nutrition !is CardState.Loaded) {
                    _ui.update { it.copy(nutrition = CardState.Loaded(c)) }
                }
            }
        }
        runCatching { nutrition.loadToday() }
            .onSuccess { d -> _ui.update { it.copy(nutrition = CardState.Loaded(d)) } }
            .onFailure {
                if (_ui.value.nutrition !is CardState.Loaded) {
                    _ui.update { it.copy(nutrition = CardState.Error("Couldn't load nutrition")) }
                }
            }
            .isSuccess
    }

    // Stale-while-revalidate: the recent feed is a server-derived aggregate with
    // no Room mirror, so it carries its own persisted single-slot cache. Show the
    // last cached feed instantly (cold start / offline) while a fresh pull runs;
    // on a failed pull keep the cache on screen and only surface an error when
    // there's nothing cached at all.
    private fun loadRecentActivity() = viewModelScope.async {
        if (_ui.value.recentActivity !is CardState.Loaded) {
            runCatching { recent.cachedRecentActivity() }.getOrNull()?.let { cached ->
                if (_ui.value.recentActivity !is CardState.Loaded) {
                    _ui.update { it.copy(recentActivity = CardState.Loaded(cached)) }
                }
            }
        }
        runCatching { recent.loadRecentActivity() }
            .onSuccess { d -> _ui.update { it.copy(recentActivity = CardState.Loaded(d)) } }
            .onFailure {
                val cached = runCatching { recent.cachedRecentActivity() }.getOrNull()
                _ui.update {
                    it.copy(
                        recentActivity = cached?.let { c -> CardState.Loaded(c) }
                            ?: CardState.Error("Couldn't load activity"),
                    )
                }
            }
            .isSuccess
    }

    // The avatar isn't a card — on failure we just leave the previous value
    // (or null), and the header falls back to the fixture initials.
    private fun loadUser() = viewModelScope.launch {
        runCatching { profile.get() }.getOrNull()?.let { p ->
            val name = p.displayName?.trim().orEmpty()
            val initials = if (name.isNotEmpty()) initialsFor(name) else USER_INITIALS
            _ui.update {
                it.copy(
                    user = DashboardUser(initials = initials, photoUrl = p.photoUrl),
                    hiddenBiometrics = p.hiddenBiometrics.toSet(),
                )
            }
        }
    }

    // Resolve today's calendar pick (completed-recap or next-to-start). The
    // in-progress draft is handled reactively in [combineDraftsAndResolved].
    private fun loadTodayWorkout() = viewModelScope.launch {
        runCatching { workouts.resolveTodayWorkout() }
            .onSuccess { pick -> todaySession.value = pick.takeUnless { it is TodayWorkout.Hidden } }
    }

    private companion object {
        /** Skip resume-driven refreshes that land within this window of the last. */
        const val REFRESH_TTL_MS = 30_000L

        /** Fixture display name → initials when no live profile name is present
         *  (Android `DashboardFallbacks.USER_NAME` / `USER_INITIALS`). */
        const val USER_NAME = "Evan Glazier"
        val USER_INITIALS: String get() = initialsFor(USER_NAME)
    }
}

/**
 * Initials from a display name — verbatim port of Android's
 * `mobile.dashboard.initialsFor`: first + last word letters, or the first two
 * letters for a single-word name, or "—" when there is no name.
 */
internal fun initialsFor(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return when (parts.size) {
        0 -> "—"
        1 -> parts[0].take(2).uppercase()
        else -> "${parts.first().first()}${parts.last().first()}".uppercase()
    }
}
