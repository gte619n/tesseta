package com.gte619n.healthfitness.mobile.dashboard

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummary
import com.gte619n.healthfitness.domain.dashboard.DailyMetricPoint
import com.gte619n.healthfitness.domain.dashboard.DashboardBloodMarkerRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardBodyCompositionRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardDailyMetricsRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardNutritionRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardRecentActivityRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardTodaysDosesRepository
import com.gte619n.healthfitness.domain.dashboard.RecentActivityEntry
import com.gte619n.healthfitness.domain.dashboard.TodaysDoseSummary
import com.gte619n.healthfitness.domain.dashboard.WeightSummary
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.data.profile.ProfileRepository
import com.gte619n.healthfitness.data.sync.LocalWriteBus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

// IMPL-AND-01: per-card state envelope so each dashboard card loads, errors,
// and retries independently.
sealed interface CardState<out T> {
    data object Loading : CardState<Nothing>
    data class Loaded<T>(val data: T) : CardState<T>
    data class Error(val message: String, val cause: Throwable? = null) : CardState<Nothing>
}

// Identity shown in the dashboard header avatar. `initials` are derived from
// the live display name (falling back to fixtures); `photoUrl` is the Google
// picture when available. Null until the profile loads — the header falls back
// to fixtures in the meantime.
data class DashboardUser(val initials: String, val photoUrl: String?)

data class DashboardUiState(
    val bodyComposition: CardState<WeightSummary?>,
    val dailyMetrics: CardState<List<DailyMetricPoint>>,
    val blood: CardState<List<BloodMarkerSummary>>,
    val todaysDoses: CardState<List<TodaysDoseSummary>>,
    val nutrition: CardState<NutritionDay>,
    val recentActivity: CardState<List<RecentActivityEntry>>,
    val user: DashboardUser? = null,
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
            CardState.Loading,
        )
    }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bodyComp: DashboardBodyCompositionRepository,
    private val dailyMetrics: DashboardDailyMetricsRepository,
    private val blood: DashboardBloodMarkerRepository,
    private val doses: DashboardTodaysDosesRepository,
    private val nutrition: DashboardNutritionRepository,
    private val recent: DashboardRecentActivityRepository,
    private val profile: ProfileRepository,
    localWriteBus: LocalWriteBus,
    unitPrefs: UnitPreferencesRepository,
) : ViewModel() {
    private val _ui = MutableStateFlow(DashboardUiState.initial)
    val uiState: StateFlow<DashboardUiState> = _ui.asStateFlow()

    /** Weight display unit; the hero/vital cards format pounds → this unit. */
    val weightUnit: StateFlow<WeightUnit> = unitPrefs.preferences
        .map { it.weight }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.POUNDS)

    // Monotonic timestamp of the last refresh we kicked off (SystemClock so it's
    // immune to wall-clock changes). Used to skip the ~6 network calls triggered
    // on every ON_RESUME when the user simply navigates back to the dashboard.
    private var lastRefreshAt: Long = 0L

    init {
        refresh()
        // Workstream D: a local write anywhere (dose logged, weight added, meal
        // captured…) should immediately re-fetch the non-reactive cards — the
        // recent-activity feed and the not-yet-mirrored reads — rather than
        // waiting for the next resume/TTL. Mirror-backed cards already update
        // reactively from the optimistic write; this closes the gap for the rest.
        // Debounced so a burst of writes (e.g. a multi-set log) coalesces.
        observeLocalWrites(localWriteBus)
    }

    @OptIn(FlowPreview::class)
    private fun observeLocalWrites(localWriteBus: LocalWriteBus) {
        viewModelScope.launch {
            localWriteBus.writes
                .debounce(250)
                .collect { refresh(force = true) }
        }
    }

    /**
     * Reloads every dashboard card. On resume this fires on every navigation
     * back, so non-forced calls within [REFRESH_TTL_MS] of the previous refresh
     * are skipped. The first load (lastRefreshAt == 0) and any [force] = true
     * caller (pull-to-refresh / explicit) always go through.
     */
    fun refresh(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
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
                loadDoses(),
                loadNutrition(),
                loadRecentActivity(),
            ).awaitAll()
            // Stamp when at least one primary card loaded; if everything failed,
            // leave lastRefreshAt untouched so the next resume retries. The
            // wall-clock stamp feeds the header's "Updated …" line; the monotonic
            // one gates resume-driven refreshes (SystemClock for TTL).
            if (results.any { it }) {
                lastRefreshAt = SystemClock.elapsedRealtime()
                _ui.update { it.copy(lastUpdated = Instant.now()) }
            }
        }
        loadUser()
    }

    fun retryBodyComposition() { loadBodyComposition() }
    fun retryDailyMetrics() { loadDailyMetrics() }
    fun retryBlood() { loadBlood() }
    fun retryDoses() { loadDoses() }
    fun retryNutrition() { loadNutrition() }
    fun retryRecentActivity() { loadRecentActivity() }

    // Each loader returns a Deferred<Boolean> — true on success — so refresh()
    // can settle the whole batch before deciding whether to stamp the TTL.
    // Retry methods ignore the result; they are never TTL-gated.
    //
    // offline-fix — cache-first, revalidate in the background: a loader first seeds
    // the card from the repository's local-only cache (Room mirror / DataStore) when
    // it has no data yet, so a cold open shows the last-synced value INSTANTLY with
    // no spinner, then it revalidates from the network. A network failure keeps the
    // cached (or already-Loaded) value on screen and only surfaces an Error when
    // there was nothing to show — so the only blocking loader left is the genuine
    // first sync (no cache yet), which is gated separately by SettingUpScreen. This
    // also subsumes the prior stale-while-revalidate behaviour for resume refreshes.
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
            .onFailure { t ->
                if (_ui.value.bodyComposition !is CardState.Loaded) {
                    _ui.update { it.copy(bodyComposition = CardState.Error("Couldn't load weight", t)) }
                }
            }
            .isSuccess
    }

    private fun loadDailyMetrics() = viewModelScope.async {
        if (_ui.value.dailyMetrics !is CardState.Loaded) {
            val cached = runCatching { dailyMetrics.cachedRecent() }.getOrNull()
            if (!cached.isNullOrEmpty() && _ui.value.dailyMetrics !is CardState.Loaded) {
                _ui.update { it.copy(dailyMetrics = CardState.Loaded(cached)) }
            }
        }
        runCatching { dailyMetrics.loadRecent() }
            .onSuccess { d -> _ui.update { it.copy(dailyMetrics = CardState.Loaded(d)) } }
            .onFailure { t ->
                if (_ui.value.dailyMetrics !is CardState.Loaded) {
                    _ui.update { it.copy(dailyMetrics = CardState.Error("Couldn't load metrics", t)) }
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
            .onFailure { t ->
                if (_ui.value.blood !is CardState.Loaded) {
                    _ui.update { it.copy(blood = CardState.Error("Couldn't load blood", t)) }
                }
            }
            .isSuccess
    }

    private fun loadDoses() = viewModelScope.async {
        if (_ui.value.todaysDoses !is CardState.Loaded) {
            runCatching { doses.cachedToday() }.getOrNull()?.let { c ->
                if (_ui.value.todaysDoses !is CardState.Loaded) {
                    _ui.update { it.copy(todaysDoses = CardState.Loaded(c)) }
                }
            }
        }
        runCatching { doses.loadToday() }
            .onSuccess { d -> _ui.update { it.copy(todaysDoses = CardState.Loaded(d)) } }
            .onFailure { t ->
                if (_ui.value.todaysDoses !is CardState.Loaded) {
                    _ui.update { it.copy(todaysDoses = CardState.Error("Couldn't load doses", t)) }
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
            .onFailure { t ->
                if (_ui.value.nutrition !is CardState.Loaded) {
                    _ui.update { it.copy(nutrition = CardState.Error("Couldn't load nutrition", t)) }
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
            recent.cachedRecent()?.let { cached ->
                if (_ui.value.recentActivity !is CardState.Loaded) {
                    _ui.update { it.copy(recentActivity = CardState.Loaded(cached)) }
                }
            }
        }
        runCatching { recent.loadRecent() }
            .onSuccess { d -> _ui.update { it.copy(recentActivity = CardState.Loaded(d)) } }
            .onFailure { t ->
                val cached = recent.cachedRecent()
                _ui.update {
                    it.copy(
                        recentActivity = cached?.let { c -> CardState.Loaded(c) }
                            ?: CardState.Error("Couldn't load activity", t),
                    )
                }
            }
            .isSuccess
    }

    // The avatar isn't a card — on failure we just leave the previous value
    // (or null), and the header falls back to the fixture initials.
    private fun loadUser() = viewModelScope.launch {
        profile.get().onSuccess { p ->
            val name = p.displayName?.trim().orEmpty()
            val initials = if (name.isNotEmpty()) initialsFor(name) else DashboardFallbacks.USER_INITIALS
            _ui.update { it.copy(user = DashboardUser(initials = initials, photoUrl = p.photoUrl)) }
        }
    }

    private companion object {
        /** Skip resume-driven refreshes that land within this window of the last. */
        const val REFRESH_TTL_MS = 30_000L
    }
}
