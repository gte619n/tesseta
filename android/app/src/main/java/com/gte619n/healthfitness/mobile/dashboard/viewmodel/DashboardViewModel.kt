package com.gte619n.healthfitness.mobile.dashboard.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionRepository
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummaryRepository
import com.gte619n.healthfitness.domain.dashboard.TodaysDosesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Dashboard ViewModel — one per screen, three per-card sub-states.
 *
 * Per-card error handling: any throwable in a repo call transitions
 * only that card into `Error`; the others keep whatever they had.
 *
 * Refresh: `init` subscribes to the body-composition snapshot Flow and
 * fires the initial parallel load. `refresh()` re-fires all three
 * independently from `Lifecycle.Event.ON_RESUME` in the screens.
 * Per-card retry callbacks live on the VM so a flaky Blood fetch
 * doesn't force the user to refresh the entire dashboard.
 *
 * Round 2 Stage C: body composition now consumes the canonical
 * `domain.bodycomposition.BodyCompositionRepository` (snapshot Flow),
 * not the retired `domain.dashboard.BodyCompositionRepository` (one-shot
 * `loadRecent()`). The hero composable builds a `WeightHeroDisplay`
 * from the snapshot at render time.
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val bodyComp: BodyCompositionRepository,
    private val blood: BloodMarkerSummaryRepository,
    private val doses: TodaysDosesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DashboardUiState.initial)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // Subscribe to the snapshot Flow once — every refresh re-emits
        // through the same hot flow so we don't need to re-subscribe.
        viewModelScope.launch {
            bodyComp.observeSnapshot().collect { snapshot ->
                _uiState.update { it.copy(bodyComposition = CardState.Loaded(snapshot)) }
            }
        }
        refresh()
    }

    /** Re-fire all three loads in parallel (independent coroutines). */
    fun refresh() {
        loadBodyComposition()
        loadBlood()
        loadDoses()
    }

    fun retryBodyComposition(): Job = loadBodyComposition()
    fun retryBlood(): Job = loadBlood()
    fun retryDoses(): Job = loadDoses()

    private fun loadBodyComposition(): Job = viewModelScope.launch {
        _uiState.update { it.copy(bodyComposition = CardState.Loading) }
        runCatching { bodyComp.refresh() }
            .onFailure { cause ->
                _uiState.update {
                    it.copy(
                        bodyComposition = CardState.Error(
                            message = "Couldn't load weight",
                            cause = cause,
                        ),
                    )
                }
            }
        // Success path emits via the snapshot flow → the init-time
        // collector flips the card to Loaded.
    }

    private fun loadBlood(): Job = viewModelScope.launch {
        _uiState.update { it.copy(blood = CardState.Loading) }
        runCatching { blood.loadDashboardMarkers() }
            .onSuccess { data ->
                _uiState.update { it.copy(blood = CardState.Loaded(data)) }
            }
            .onFailure { cause ->
                _uiState.update {
                    it.copy(
                        blood = CardState.Error(
                            message = "Couldn't load blood panel",
                            cause = cause,
                        ),
                    )
                }
            }
    }

    private fun loadDoses(): Job = viewModelScope.launch {
        _uiState.update { it.copy(todaysDoses = CardState.Loading) }
        runCatching { doses.loadToday() }
            .onSuccess { data ->
                _uiState.update { it.copy(todaysDoses = CardState.Loaded(data)) }
            }
            .onFailure { cause ->
                _uiState.update {
                    it.copy(
                        todaysDoses = CardState.Error(
                            message = "Couldn't load today's doses",
                            cause = cause,
                        ),
                    )
                }
            }
    }
}
