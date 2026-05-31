package com.gte619n.healthfitness.mobile.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummary
import com.gte619n.healthfitness.domain.dashboard.DashboardBloodMarkerRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardBodyCompositionRepository
import com.gte619n.healthfitness.domain.dashboard.DashboardTodaysDosesRepository
import com.gte619n.healthfitness.domain.dashboard.TodaysDoseSummary
import com.gte619n.healthfitness.domain.dashboard.WeightSummary
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.domain.profile.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val blood: CardState<List<BloodMarkerSummary>>,
    val todaysDoses: CardState<List<TodaysDoseSummary>>,
    val user: DashboardUser? = null,
) {
    companion object {
        val initial = DashboardUiState(CardState.Loading, CardState.Loading, CardState.Loading)
    }
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bodyComp: DashboardBodyCompositionRepository,
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

    init { refresh() }

    fun refresh() {
        loadBodyComposition()
        loadBlood()
        loadDoses()
        loadUser()
    }

    fun retryBodyComposition() = loadBodyComposition()
    fun retryBlood() = loadBlood()
    fun retryDoses() = loadDoses()

    private fun loadBodyComposition() = viewModelScope.launch {
        _ui.update { it.copy(bodyComposition = CardState.Loading) }
        runCatching { bodyComp.loadRecent() }
            .onSuccess { d -> _ui.update { it.copy(bodyComposition = CardState.Loaded(d)) } }
            .onFailure { t -> _ui.update { it.copy(bodyComposition = CardState.Error("Couldn't load weight", t)) } }
    }

    private fun loadBlood() = viewModelScope.launch {
        _ui.update { it.copy(blood = CardState.Loading) }
        runCatching { blood.loadDashboardMarkers() }
            .onSuccess { d -> _ui.update { it.copy(blood = CardState.Loaded(d)) } }
            .onFailure { t -> _ui.update { it.copy(blood = CardState.Error("Couldn't load blood", t)) } }
    }

    private fun loadDoses() = viewModelScope.launch {
        _ui.update { it.copy(todaysDoses = CardState.Loading) }
        runCatching { doses.loadToday() }
            .onSuccess { d -> _ui.update { it.copy(todaysDoses = CardState.Loaded(d)) } }
            .onFailure { t -> _ui.update { it.copy(todaysDoses = CardState.Error("Couldn't load doses", t)) } }
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
}
