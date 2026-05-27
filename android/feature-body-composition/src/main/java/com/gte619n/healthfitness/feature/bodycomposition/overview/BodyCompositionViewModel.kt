package com.gte619n.healthfitness.feature.bodycomposition.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionRepository
import com.gte619n.healthfitness.domain.bodycomposition.BodyCompositionSnapshot
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanRepository
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the body-composition overview screen. Combines the body-comp
 * snapshot flow and the DEXA-scan list flow into one UI state, fires
 * both refreshes in parallel on first composition and on pull-to-refresh.
 */
@HiltViewModel
class BodyCompositionViewModel @Inject constructor(
    private val bodyRepo: BodyCompositionRepository,
    private val dexaRepo: DexaScanRepository,
) : ViewModel() {

    data class UiState(
        val snapshot: BodyCompositionSnapshot? = null,
        val dexaScans: List<DexaScanSummary> = emptyList(),
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                bodyRepo.observeSnapshot().onEach { /* receive */ },
                dexaRepo.observeScans().onEach { /* receive */ },
            ) { snap, scans -> snap to scans }
                .collect { (snap, scans) ->
                    _state.update {
                        it.copy(snapshot = snap, dexaScans = scans, loading = false)
                    }
                }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching {
                coroutineScope {
                    launch { bodyRepo.refresh() }
                    launch { dexaRepo.refreshScans() }
                }
            }.onFailure { e ->
                _state.update {
                    it.copy(loading = false, error = e.localizedMessage ?: "Could not load")
                }
            }.onSuccess {
                _state.update { it.copy(loading = false) }
            }
        }
    }
}
