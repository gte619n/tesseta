package com.gte619n.healthfitness.mobile.dashboard

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummary
import com.gte619n.healthfitness.domain.dashboard.DailyMetricPoint
import com.gte619n.healthfitness.domain.dashboard.DashboardBloodMarkerRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardBodyCompositionRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardDailyMetricsRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardTodaysDosesRepository
import com.gte619n.healthfitness.domain.dashboard.TodaysDoseSummary
import com.gte619n.healthfitness.domain.dashboard.WeightSummary
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.domain.profile.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val user: DashboardUser? = null,
) {
    companion object {
        val initial = DashboardUiState(
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
    private val profile: ProfileRepository,
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

    init { refresh() }

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
            ).awaitAll()
            // Stamp when at least one primary card loaded; if everything failed,
            // leave lastRefreshAt untouched so the next resume retries.
            if (results.any { it }) lastRefreshAt = SystemClock.elapsedRealtime()
        }
        loadUser()
    }

    fun retryBodyComposition() { loadBodyComposition() }
    fun retryDailyMetrics() { loadDailyMetrics() }
    fun retryBlood() { loadBlood() }
    fun retryDoses() { loadDoses() }

    // Each loader returns a Deferred<Boolean> — true on success — so refresh()
    // can settle the whole batch before deciding whether to stamp the TTL.
    // Retry methods ignore the result; they are never TTL-gated.
    //
    // Stale-while-revalidate: a loader only drops to CardState.Loading when it has
    // no data yet (first load, or recovering from an error). Once a card is Loaded,
    // a resume-driven refresh keeps the current values on screen and swaps in the
    // new data when it arrives — no spinner flash, which is what made resuming the
    // dashboard read as sluggish / "not refreshing".
    private fun loadBodyComposition() = viewModelScope.async {
        if (_ui.value.bodyComposition !is CardState.Loaded) _ui.update { it.copy(bodyComposition = CardState.Loading) }
        runCatching { bodyComp.loadRecent() }
            .onSuccess { d -> _ui.update { it.copy(bodyComposition = CardState.Loaded(d)) } }
            .onFailure { t -> _ui.update { it.copy(bodyComposition = CardState.Error("Couldn't load weight", t)) } }
            .isSuccess
    }

    private fun loadDailyMetrics() = viewModelScope.async {
        if (_ui.value.dailyMetrics !is CardState.Loaded) _ui.update { it.copy(dailyMetrics = CardState.Loading) }
        runCatching { dailyMetrics.loadRecent() }
            .onSuccess { d -> _ui.update { it.copy(dailyMetrics = CardState.Loaded(d)) } }
            .onFailure { t -> _ui.update { it.copy(dailyMetrics = CardState.Error("Couldn't load metrics", t)) } }
            .isSuccess
    }

    private fun loadBlood() = viewModelScope.async {
        if (_ui.value.blood !is CardState.Loaded) _ui.update { it.copy(blood = CardState.Loading) }
        runCatching { blood.loadDashboardMarkers() }
            .onSuccess { d -> _ui.update { it.copy(blood = CardState.Loaded(d)) } }
            .onFailure { t -> _ui.update { it.copy(blood = CardState.Error("Couldn't load blood", t)) } }
            .isSuccess
    }

    private fun loadDoses() = viewModelScope.async {
        if (_ui.value.todaysDoses !is CardState.Loaded) _ui.update { it.copy(todaysDoses = CardState.Loading) }
        runCatching { doses.loadToday() }
            .onSuccess { d -> _ui.update { it.copy(todaysDoses = CardState.Loaded(d)) } }
            .onFailure { t -> _ui.update { it.copy(todaysDoses = CardState.Error("Couldn't load doses", t)) } }
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
