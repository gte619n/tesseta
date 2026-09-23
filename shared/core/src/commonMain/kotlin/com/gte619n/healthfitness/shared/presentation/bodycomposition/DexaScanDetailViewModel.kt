package com.gte619n.healthfitness.shared.presentation.bodycomposition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.DexaScanRepository
import com.gte619n.healthfitness.shared.data.UnitPreferencesRepository
import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaRegion
import com.gte619n.healthfitness.shared.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.shared.domain.prefs.WeightUnit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave E2 — shared DEXA scan detail VM (per-region grid +
 * optimistic inline field edits). Port of android/feature-body-composition/.../
 * detail/DexaScanDetailViewModel. Android's `SnackbarController` (a UI singleton)
 * is replaced by a platform-neutral [messages] one-shot [SharedFlow] the view
 * observes for transient errors; the PDF-view Intent is Android-only, so the
 * shared VM exposes only [downloadPdf]. [scanId] is passed in by the platform nav.
 */
class DexaScanDetailViewModel(
    private val repo: DexaScanRepository,
    unitPrefsRepo: UnitPreferencesRepository,
    private val scanId: String,
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

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Transient, one-shot messages (parity with the Android snackbar). */
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

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
     * background, reverts + emits a message on failure.
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
                    _messages.tryEmit("Couldn't save: ${e.message ?: "error"}")
                }
        }
    }

    /** Fetch the scan's source PDF bytes; the SwiftUI view previews via QuickLook. */
    suspend fun downloadPdf(): ByteArray = repo.downloadPdf(scanId)

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            runCatching { repo.deleteScan(scanId) }
                .onSuccess {
                    _messages.tryEmit("Scan deleted")
                    onDone()
                }
                .onFailure { e ->
                    _state.update { it.copy(deleting = false) }
                    _messages.tryEmit("Couldn't delete: ${e.message ?: "error"}")
                }
        }
    }
}

/**
 * Applies an optimistic field update mirroring the backend path-string
 * convention. Top-level paths (e.g. "totalMassLb") and region-scoped paths
 * (e.g. "trunk.leanTissueLb") are supported. Ported verbatim from Android.
 */
internal fun DexaScan.withFieldPatched(path: String, value: Double?): DexaScan {
    val segments = path.split(".")
    return if (segments.size == 1) {
        withTopLevelPatched(segments[0], value)
    } else {
        withRegionPatched(segments[0], segments[1], value)
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
