package com.gte619n.healthfitness.feature.bodycomposition.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.data.bodycomposition.BodyCompositionRepository
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.data.bodycomposition.DexaScanRepository
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.data.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BodyCompositionViewModel @Inject constructor(
    private val bodyRepo: BodyCompositionRepository,
    private val dexaRepo: DexaScanRepository,
    unitPrefsRepo: UnitPreferencesRepository,
) : ViewModel() {

    val weightUnit: StateFlow<WeightUnit> =
        unitPrefsRepo.preferences
            .map { it.weight }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.POUNDS)

    data class UiState(
        val snapshot: BodyCompositionSnapshot? = null,
        val dexaScans: List<DexaScanSummary> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Monotonic timestamp of the last successful background refresh, so a re-entry
    // within REFRESH_TTL_MS reuses the mirror instead of re-hitting the network.
    private var lastRefreshAt: Long = 0L

    init {
        // Offline-first: the screen renders from the Room mirror the instant it
        // emits (loading flips false below); the network refresh is background
        // revalidation only — it never blanks the screen back to a spinner.
        viewModelScope.launch {
            combine(
                bodyRepo.observeSnapshot(),
                dexaRepo.observeScans(),
            ) { snap, scans -> snap to scans }
                .collect { (snap, scans) ->
                    _state.update {
                        it.copy(snapshot = snap, dexaScans = scans, loading = false)
                    }
                }
        }
        refreshIfStale()
    }

    /**
     * Pull-to-refresh entry point: always re-pulls (an explicit user gesture is
     * never rate-limited). Never toggles the loading spinner — the reactive mirror
     * stream owns what the user sees.
     */
    fun refresh() = doRefresh()

    /** On-entry revalidation: skipped when the mirror was refreshed recently. */
    private fun refreshIfStale() {
        val now = nowMs()
        if (lastRefreshAt != 0L && now - lastRefreshAt < REFRESH_TTL_MS) return
        doRefresh()
    }

    private fun doRefresh() {
        viewModelScope.launch {
            _state.update { it.copy(error = null) }
            runCatching {
                coroutineScope {
                    launch { bodyRepo.refresh() }
                    launch { dexaRepo.refreshScans() }
                }
            }.fold(
                onSuccess = { lastRefreshAt = nowMs() },
                onFailure = { e ->
                    // Keep any mirror data on screen; only surface an error when we
                    // have nothing to show yet.
                    _state.update {
                        if (it.snapshot == null && it.dexaScans.isEmpty()) {
                            it.copy(loading = false, error = e.message ?: "Could not load")
                        } else {
                            it
                        }
                    }
                },
            )
        }
    }

    // Monotonic wall-independent millis. System.nanoTime (not SystemClock) so the
    // TTL guard is exercisable in plain JVM unit tests.
    private fun nowMs(): Long = System.nanoTime() / 1_000_000L

    private companion object {
        const val REFRESH_TTL_MS = 30_000L
    }
}
