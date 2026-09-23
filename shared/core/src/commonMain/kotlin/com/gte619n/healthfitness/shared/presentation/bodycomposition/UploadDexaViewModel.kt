package com.gte619n.healthfitness.shared.presentation.bodycomposition

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.ConnectivityMonitor
import com.gte619n.healthfitness.shared.data.DexaScanRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave E2 — shared DEXA-PDF upload VM (online-only AI flow,
 * D17/#41). Port of android/feature-body-composition/.../upload/
 * UploadDexaViewModel. Android's `SnackbarController` is replaced by a
 * platform-neutral [messages] one-shot [SharedFlow]. The picked PDF's
 * `(fileName, bytes)` come from the platform document picker; [isOnline] gates it.
 */
class UploadDexaViewModel(
    private val repo: DexaScanRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    // D17/#41: the DEXA PDF extraction is an online-only AI flow. The screen gates
    // the picker behind this and shows a "needs connection" affordance when false.
    val isOnline: StateFlow<Boolean> = connectivity.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    sealed interface UiState {
        data object Idle : UiState
        data class InProgress(val phase: String, val message: String?) : UiState
        data class Complete(val scanId: String) : UiState
        data class Failed(val error: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun upload(fileName: String, bytes: ByteArray) {
        if (bytes.size > MAX_PDF_BYTES) {
            val msg = "PDF exceeds 25 MB limit"
            _state.value = UiState.Failed(msg)
            _messages.tryEmit(msg)
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
