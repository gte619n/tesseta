package com.gte619n.healthfitness.feature.bodycomposition.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.bodycomposition.DexaScan
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanRepository
import com.gte619n.healthfitness.domain.bodycomposition.DexaUploadEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the DEXA upload sheet. The screen picks a PDF, hands the bytes
 * to [upload], and observes [state] for phase transitions matching the
 * backend's SSE phase events (`uploading` → `extracting` → `saving` →
 * `complete` | `failed`). Mirrors [com.gte619n.healthfitness.feature.blood.upload.UploadLabReportViewModel].
 */
@HiltViewModel
class UploadDexaViewModel @Inject constructor(
    private val repo: DexaScanRepository,
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data class InProgress(val phase: String, val message: String?) : UiState
        data class Complete(val scan: DexaScan) : UiState
        data class Failed(val error: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var job: Job? = null

    fun upload(fileName: String, bytes: ByteArray) {
        if (bytes.size > MAX_PDF_BYTES) {
            _state.value = UiState.Failed("PDF exceeds 25 MB limit")
            return
        }
        if (bytes.isEmpty()) {
            _state.value = UiState.Failed("PDF is empty")
            return
        }
        job?.cancel()
        job = viewModelScope.launch {
            repo.uploadPdf(fileName, bytes)
                .onStart { _state.value = UiState.InProgress("uploading", "Saving your PDF") }
                .catch { _state.value = UiState.Failed(it.localizedMessage ?: "Upload failed") }
                .collect { event ->
                    _state.value = when (event) {
                        is DexaUploadEvent.Phase ->
                            UiState.InProgress(event.phase, event.message)
                        is DexaUploadEvent.Complete -> UiState.Complete(event.scan)
                        is DexaUploadEvent.Failed -> UiState.Failed(event.error)
                    }
                }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = UiState.Idle
    }

    companion object {
        const val MAX_PDF_BYTES = 25L * 1024 * 1024
    }
}
