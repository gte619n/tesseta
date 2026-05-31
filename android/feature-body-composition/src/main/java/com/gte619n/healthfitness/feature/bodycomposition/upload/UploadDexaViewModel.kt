package com.gte619n.healthfitness.feature.bodycomposition.upload

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanRepository
import com.gte619n.healthfitness.domain.bodycomposition.DexaUploadEvent
import com.gte619n.healthfitness.ui.snackbar.SnackbarController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadDexaViewModel @Inject constructor(
    private val repo: DexaScanRepository,
    private val snackbar: SnackbarController,
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data class InProgress(val phase: String, val message: String?) : UiState
        data class Complete(val scanId: String) : UiState
        data class Failed(val error: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun upload(fileName: String, bytes: ByteArray) {
        if (bytes.size > MAX_PDF_BYTES) {
            val msg = "PDF exceeds 25 MB limit"
            _state.value = UiState.Failed(msg)
            snackbar.showError(msg)
            return
        }
        viewModelScope.launch {
            _state.value = UiState.InProgress("uploading", "Saving your PDF")
            repo.uploadPdf(fileName, bytes)
                .catch { e -> _state.value = UiState.Failed(e.message ?: "Upload failed") }
                .collect { event ->
                    when (event) {
                        is DexaUploadEvent.Phase ->
                            _state.value = UiState.InProgress(event.phase, event.message)
                        is DexaUploadEvent.Complete ->
                            _state.value = UiState.Complete(event.scan.scanId)
                        is DexaUploadEvent.Failed ->
                            _state.value = UiState.Failed(event.error)
                    }
                }
        }
    }

    fun reset() {
        _state.value = UiState.Idle
    }

    companion object {
        const val MAX_PDF_BYTES = 25L * 1024 * 1024
    }
}
