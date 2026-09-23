package com.gte619n.healthfitness.shared.presentation.bodycomposition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.BodyCompositionRepository
import com.gte619n.healthfitness.shared.data.DexaScanRepository
import com.gte619n.healthfitness.shared.data.UnitPreferencesRepository
import com.gte619n.healthfitness.shared.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.shared.domain.prefs.WeightUnit
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

/**
 * IMPL-IOS-01 Phase 3 Wave E2 — shared body-composition overview VM (weight/
 * body-fat hero + trend chart + DEXA-scan grid). Port of android/
 * feature-body-composition/.../overview/BodyCompositionViewModel (Hilt → plain
 * constructor). Offline-first: the reactive mirror stream owns what the user sees;
 * the network refresh is background revalidation only and never blanks the screen.
 */
class BodyCompositionViewModel(
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

    // See BloodOverviewViewModel — monotone-enough millis for the TTL guard.
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

    private fun nowMs(): Long = getMonotonicMillis()

    private companion object {
        const val REFRESH_TTL_MS = 30_000L
    }
}

/** Monotone-enough millis for the 30s TTL guard; keeps the VM in commonMain. */
private val monotonicOrigin: kotlinx.datetime.Instant = kotlinx.datetime.Clock.System.now()
private fun getMonotonicMillis(): Long =
    (kotlinx.datetime.Clock.System.now() - monotonicOrigin).inWholeMilliseconds
