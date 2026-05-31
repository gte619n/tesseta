package com.gte619n.healthfitness.feature.blood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.domain.blood.BloodTestReportRepository
import com.gte619n.healthfitness.domain.blood.UploadEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

@HiltViewModel
class UploadLabReportViewModel @Inject constructor(
    private val reports: BloodTestReportRepository,
) : ViewModel() {

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
                .catch { _state.value = UiState.Failed(it.localizedMessage ?: "Upload failed") }
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
