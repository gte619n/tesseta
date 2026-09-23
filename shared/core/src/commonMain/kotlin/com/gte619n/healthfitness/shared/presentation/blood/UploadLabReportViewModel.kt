package com.gte619n.healthfitness.shared.presentation.blood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.BloodTestReportRepository
import com.gte619n.healthfitness.shared.data.ConnectivityMonitor
import com.gte619n.healthfitness.shared.domain.blood.BloodTestReport
import com.gte619n.healthfitness.shared.domain.blood.UploadEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave E1 — shared lab-PDF upload VM (online-only AI flow,
 * D17). Port of android/feature-blood/.../UploadLabReportViewModel. The picked
 * PDF's `(fileName, bytes)` come from the platform document picker (PHPicker /
 * UIDocumentPicker on iOS, SAF on Android); the shared VM streams the backend's
 * upload → extract → save phases into a sealed [UiState]. Nothing is queued
 * offline — [isOnline] gates the picker.
 */
class UploadLabReportViewModel(
    private val reports: BloodTestReportRepository,
    connectivity: ConnectivityMonitor,
) : ViewModel() {

    // D17: the PDF upload is an online-only AI flow. The screen disables the picker
    // and shows a "needs connection" affordance when this is false.
    val isOnline: StateFlow<Boolean> = connectivity.isOnline
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    sealed interface UiState {
        data object Idle : UiState
        data object Uploading : UiState
        data object Extracting : UiState
        data object Saving : UiState
        data class Complete(val report: BloodTestReport) : UiState
        data class Failed(val error: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var job: Job? = null

    fun upload(fileName: String, bytes: ByteArray) {
        job?.cancel()
        job = viewModelScope.launch {
            reports.upload(fileName, bytes)
                .onStart { _state.value = UiState.Uploading }
                .catch { _state.value = UiState.Failed(it.message ?: "Upload failed") }
                .collect { event ->
                    _state.value = when (event) {
                        UploadEvent.Uploading -> UiState.Uploading
                        UploadEvent.Extracting -> UiState.Extracting
                        UploadEvent.Saving -> UiState.Saving
                        is UploadEvent.Complete -> UiState.Complete(event.report)
                        is UploadEvent.Failed -> UiState.Failed(event.error)
                    }
                }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = UiState.Idle
    }
}
