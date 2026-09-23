package com.gte619n.healthfitness.shared.presentation.medications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.AdherenceRepository
import com.gte619n.healthfitness.shared.data.MedicationCrudRepository
import com.gte619n.healthfitness.shared.domain.medications.TodaysDose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * IMPL-IOS-01 Phase 3 Wave B — port of the Android
 * `feature-medical/.../today/TodaysDosesViewModel.kt` to KMP `commonMain`.
 * Follows the reference [MedicationsViewModel] shape (D2): `androidx.lifecycle
 * .ViewModel`, plain-constructor repositories wired by platform DI, a sealed
 * UiState + one StateFlow, suspend intents. SKIE bridges [state] to SwiftUI via
 * the ObservableViewModel wrapper (TodaysDosesView.swift).
 *
 * State-management Phase 1 (see the Android original): the checklist is driven by
 * ONE reactive source ([MedicationCrudRepository.observeTodaysDoses] — cached
 * projection overlaid with the adherence mirror). A dose logged/undone anywhere
 * (the D9 local-notification "Take"/"Take all" action, the in-app toggle, or a
 * remote sync) re-emits here and updates the list live; there is NO ON_RESUME
 * refetch and NO manual optimistic flip to keep in sync — that is exactly the
 * class of "home doses go stale when taken off-screen" bug this shape fixes.
 *
 * Transient errors surface via [message] (a nullable one-shot the view shows and
 * then [consumeMessage]s) rather than an Android `SnackbarController`, keeping
 * this KMP-clean while preserving the Android behavior (a failed local write is
 * non-fatal; the reactive read remains the truth).
 */
sealed interface TodaysDosesUiState {
    data object Loading : TodaysDosesUiState
    data class Ready(val doses: List<TodaysDose>) : TodaysDosesUiState
    data class Error(val message: String) : TodaysDosesUiState
}

class TodaysDosesViewModel(
    private val medications: MedicationCrudRepository,
    private val adherence: AdherenceRepository,
    private val clock: Clock = Clock.System,
    private val timeZone: TimeZone = TimeZone.currentSystemDefault(),
) : ViewModel() {

    private val _state = MutableStateFlow<TodaysDosesUiState>(TodaysDosesUiState.Loading)
    val state: StateFlow<TodaysDosesUiState> = _state.asStateFlow()

    /** One-shot transient error banner (parity with Android's snackbar). */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // Has a network revalidation resolved yet? Keeps the cold-open spinner up until
    // the first load settles, so we don't flash "no doses" before anything loaded.
    private var resolved = false

    init {
        observeDoses()
        refresh()
    }

    private fun observeDoses() {
        viewModelScope.launch {
            medications.observeTodaysDoses().collect { doses ->
                // Don't leave the cold-open spinner for an empty projection until the
                // first revalidation has resolved; a real (non-empty) or post-resolve
                // emission always wins.
                if (doses.isNotEmpty() || resolved || _state.value is TodaysDosesUiState.Ready) {
                    _state.value = TodaysDosesUiState.Ready(doses)
                }
            }
        }
    }

    /** Background revalidation; the reactive [state] shows the cached+mirror list meanwhile. */
    fun refresh() {
        viewModelScope.launch {
            runCatching { medications.refreshTodaysDoses() }
                .onFailure {
                    if (_state.value is TodaysDosesUiState.Loading) {
                        _state.value =
                            TodaysDosesUiState.Error(it.message ?: "Could not load doses")
                    }
                }
            resolved = true
            if (_state.value is TodaysDosesUiState.Loading) {
                _state.value = TodaysDosesUiState.Ready(emptyList())
            }
        }
    }

    /**
     * Toggle a dose. The adherence write is offline-first (optimistic mirror +
     * outbox); the reactive [state] reflects it the instant the mirror changes, so
     * there's no manual optimistic flip. A rare local-write failure surfaces a
     * [message]; the reactive read remains the truth.
     */
    fun toggle(dose: TodaysDose) {
        viewModelScope.launch {
            runCatching {
                if (dose.taken) {
                    adherence.undoDose(dose.medicationId, clock.todayIn(timeZone), dose.window)
                } else {
                    adherence.logDose(dose.medicationId, dose.window)
                }
            }.onFailure { _message.value = "Could not save — try again" }
        }
    }

    fun consumeMessage() { _message.value = null }
}
