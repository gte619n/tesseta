package com.gte619n.healthfitness.feature.bodycomposition.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.bodycomposition.DexaRegion
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanRepository
import com.gte619n.healthfitness.domain.prefs.UnitPreferencesRepository
import com.gte619n.healthfitness.domain.prefs.WeightUnit
import com.gte619n.healthfitness.feature.bodycomposition.nav.BodyCompositionRoutes
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
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

@HiltViewModel
class DexaScanDetailViewModel @Inject constructor(
    private val repo: DexaScanRepository,
    private val snackbar: SnackbarController,
    unitPrefsRepo: UnitPreferencesRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val weightUnit: StateFlow<WeightUnit> =
        unitPrefsRepo.preferences
            .map { it.weight }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeightUnit.POUNDS)

    data class UiState(
        val scan: DexaScan? = null,
        val loading: Boolean = true,
        val deleting: Boolean = false,
        val error: String? = null,
    )

    private val scanId: String =
        requireNotNull(savedStateHandle[BodyCompositionRoutes.ARG_SCAN_ID]) {
            "Missing ${BodyCompositionRoutes.ARG_SCAN_ID} route arg"
        }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repo.getScan(scanId) }
                .onSuccess { scan -> _state.update { it.copy(scan = scan, loading = false) } }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "Could not load") }
                }
        }
    }

    /**
     * Optimistic patch. Mutates local state immediately, fires PATCH in the
     * background, reverts + snackbar on failure.
     */
    fun patchField(path: String, value: Double?) {
        viewModelScope.launch {
            val before = _state.value.scan ?: return@launch
            val optimistic = before.withFieldPatched(path, value)
            _state.update { it.copy(scan = optimistic) }
            runCatching { repo.patchField(scanId, path, value) }
                .onSuccess { updated -> _state.update { it.copy(scan = updated) } }
                .onFailure { e ->
                    _state.update { it.copy(scan = before) }
                    snackbar.show("Couldn't save: ${e.message ?: "error"}")
                }
        }
    }

    /** Downloads the PDF and launches a system viewer. Snackbar on failure. */
    fun viewPdf(context: android.content.Context) {
        viewModelScope.launch {
            runCatching { viewDexaPdf(context, repo, scanId) }
                .onFailure { e ->
                    snackbar.show("Couldn't open PDF: ${e.message ?: "error"}")
                }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            runCatching { repo.deleteScan(scanId) }
                .onSuccess {
                    snackbar.show("Scan deleted")
                    onDone()
                }
                .onFailure { e ->
                    _state.update { it.copy(deleting = false) }
                    snackbar.show("Couldn't delete: ${e.message ?: "error"}")
                }
        }
    }
}

/**
 * Applies an optimistic field update mirroring the backend path-string
 * convention. Top-level paths (e.g. "totalMassLb") and region-scoped paths
 * (e.g. "trunk.leanTissueLb") are supported.
 */
private fun DexaScan.withFieldPatched(path: String, value: Double?): DexaScan {
    val segments = path.split(".")
    return if (segments.size == 1) {
        withTopLevelPatched(segments[0], value)
    } else {
        val regionKey = segments[0]
        val field = segments[1]
        withRegionPatched(regionKey, field, value)
    }
}

private fun DexaScan.withTopLevelPatched(field: String, value: Double?): DexaScan = when (field) {
    "totalMassLb" -> copy(totalMassLb = value)
    "leanTissueLb" -> copy(leanTissueLb = value)
    "fatTissueLb" -> copy(fatTissueLb = value)
    "totalBodyFatPercent" -> copy(totalBodyFatPercent = value)
    "visceralFatLb" -> copy(visceralFatLb = value)
    "androidGynoidRatio" -> copy(androidGynoidRatio = value)
    "bmdTScore" -> copy(bmdTScore = value)
    "bmdZScore" -> copy(bmdZScore = value)
    "restingMetabolicRateKcal" -> copy(restingMetabolicRateKcal = value?.toInt())
    else -> this
}

private fun DexaScan.withRegionPatched(regionKey: String, field: String, value: Double?): DexaScan {
    fun patch(region: DexaRegion?): DexaRegion {
        val base = region ?: DexaRegion(null, null, null, null)
        return when (field) {
            "totalMassLb" -> base.copy(totalMassLb = value)
            "leanTissueLb" -> base.copy(leanTissueLb = value)
            "fatTissueLb" -> base.copy(fatTissueLb = value)
            "regionFatPercent" -> base.copy(regionFatPercent = value)
            else -> base
        }
    }
    return when (regionKey) {
        "trunk" -> copy(trunk = patch(trunk))
        "android" -> copy(android = patch(android))
        "gynoid" -> copy(gynoid = patch(gynoid))
        "armsTotal" -> copy(armsTotal = patch(armsTotal))
        "armsRight" -> copy(armsRight = patch(armsRight))
        "armsLeft" -> copy(armsLeft = patch(armsLeft))
        "legsTotal" -> copy(legsTotal = patch(legsTotal))
        "legsRight" -> copy(legsRight = patch(legsRight))
        "legsLeft" -> copy(legsLeft = patch(legsLeft))
        else -> this
    }
}
