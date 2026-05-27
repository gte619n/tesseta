package com.gte619n.healthfitness.feature.bodycomposition.detail

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.gte619n.healthfitness.domain.bodycomposition.DexaRegion
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanRepository
import com.gte619n.healthfitness.feature.bodycomposition.nav.DexaScanDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Drives the DEXA scan detail screen.
 *
 * Numeric edits go through [patchField] — optimistic local update, PATCH
 * to the backend, snapshot to the server's authoritative response on
 * success, or revert + snackbar on failure. The snackbar surface itself
 * comes from the screen-level `LocalSnackbarController`; the VM exposes a
 * `SharedFlow<UiEvent>` for transient toasts so it stays Context-free
 * for messaging.
 *
 * The "View PDF" affordance writes the report bytes to `cacheDir/dexa/`
 * and emits an `Intent.ACTION_VIEW` the screen launches with
 * `startActivity` — same pattern as IMPL-AND-04's report detail.
 */
@HiltViewModel
class DexaScanDetailViewModel @Inject constructor(
    private val repo: DexaScanRepository,
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
) : ViewModel() {

    data class UiState(
        val scan: DexaScan? = null,
        val loading: Boolean = true,
        val deleting: Boolean = false,
        val error: String? = null,
        val transientMessage: String? = null,
    )

    sealed interface PdfStatus {
        data object Idle : PdfStatus
        data object Downloading : PdfStatus
        data object Ready : PdfStatus
        data class Error(val message: String) : PdfStatus
    }

    val scanId: String = savedState.toRoute<DexaScanDetailRoute>().scanId

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _pdfStatus = MutableStateFlow<PdfStatus>(PdfStatus.Idle)
    val pdfStatus: StateFlow<PdfStatus> = _pdfStatus.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { repo.getScan(scanId) }
                .onSuccess { scan ->
                    _state.update { it.copy(scan = scan, loading = false) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(loading = false, error = e.localizedMessage ?: "Failed to load scan")
                    }
                }
        }
    }

    /**
     * Optimistic local patch + backend PATCH. On failure, rolls back to
     * the pre-edit scan and surfaces a transient message via UiState.
     */
    fun patchField(path: String, value: Double?) {
        val before = _state.value.scan ?: return
        val optimistic = before.withFieldPatched(path, value)
        _state.update { it.copy(scan = optimistic) }
        viewModelScope.launch {
            runCatching { repo.patchField(scanId, path, value) }
                .onSuccess { updated ->
                    _state.update { it.copy(scan = updated) }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            scan = before,
                            transientMessage = "Couldn't save: ${e.localizedMessage ?: "error"}",
                        )
                    }
                }
        }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(deleting = true) }
            runCatching { repo.deleteScan(scanId) }
                .onSuccess {
                    _state.update { it.copy(deleting = false, transientMessage = "Scan deleted") }
                    onDone()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(
                            deleting = false,
                            transientMessage = "Couldn't delete: ${e.localizedMessage ?: "error"}",
                        )
                    }
                }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(transientMessage = null) }
    }

    /**
     * Downloads the PDF (if not cached), builds an `ACTION_VIEW` intent
     * over the [androidx.core.content.FileProvider] authority, and hands
     * it to [onIntent]. Caller invokes `startActivity` from a Context.
     */
    fun openPdf(onIntent: (Intent) -> Unit) {
        if (_pdfStatus.value is PdfStatus.Downloading) return
        viewModelScope.launch {
            _pdfStatus.value = PdfStatus.Downloading
            runCatching {
                val file = ensurePdfCached()
                buildViewIntent(file)
            }.onSuccess { intent ->
                _pdfStatus.value = PdfStatus.Ready
                onIntent(intent)
            }.onFailure { e ->
                _pdfStatus.value = PdfStatus.Error(e.localizedMessage ?: "Could not open PDF")
            }
        }
    }

    private suspend fun ensurePdfCached(): File {
        val dir = File(context.cacheDir, "dexa").apply { mkdirs() }
        val file = File(dir, "$scanId.pdf")
        if (!file.exists() || file.length() == 0L) {
            file.writeBytes(repo.downloadPdf(scanId))
        }
        return file
    }

    private fun buildViewIntent(file: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

/**
 * Apply a field-level edit to a [DexaScan]. Path strings match the
 * backend's `UpdateFieldRequest.path` convention used by
 * `DexaScanController.updateField` — top-level numeric fields are bare
 * names (`"totalMassLb"`), region fields are `"<region>.<field>"`
 * (`"trunk.leanTissueLb"`).
 */
internal fun DexaScan.withFieldPatched(path: String, value: Double?): DexaScan {
    val parts = path.split('.')
    return when (parts.size) {
        1 -> withTopLevel(parts[0], value)
        2 -> withRegionField(parts[0], parts[1], value)
        else -> this // Unknown shape — preserve, the backend will surface the error.
    }
}

private fun DexaScan.withTopLevel(field: String, value: Double?): DexaScan = when (field) {
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

private fun DexaScan.withRegionField(region: String, field: String, value: Double?): DexaScan {
    val current = when (region) {
        "trunk" -> trunk
        "android" -> android
        "gynoid" -> gynoid
        "armsTotal" -> armsTotal
        "armsRight" -> armsRight
        "armsLeft" -> armsLeft
        "legsTotal" -> legsTotal
        "legsRight" -> legsRight
        "legsLeft" -> legsLeft
        else -> return this
    } ?: DexaRegion(null, null, null, null)
    val updated = when (field) {
        "totalMassLb" -> current.copy(totalMassLb = value)
        "leanTissueLb" -> current.copy(leanTissueLb = value)
        "fatTissueLb" -> current.copy(fatTissueLb = value)
        "regionFatPercent" -> current.copy(regionFatPercent = value)
        else -> return this
    }
    return when (region) {
        "trunk" -> copy(trunk = updated)
        "android" -> copy(android = updated)
        "gynoid" -> copy(gynoid = updated)
        "armsTotal" -> copy(armsTotal = updated)
        "armsRight" -> copy(armsRight = updated)
        "armsLeft" -> copy(armsLeft = updated)
        "legsTotal" -> copy(legsTotal = updated)
        "legsRight" -> copy(legsRight = updated)
        "legsLeft" -> copy(legsLeft = updated)
        else -> this
    }
}
