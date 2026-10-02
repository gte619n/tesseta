package com.gte619n.healthfitness.shared.presentation.blood

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.shared.data.BloodTestReportRepository
import com.gte619n.healthfitness.shared.domain.blood.BloodTestReport
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * IMPL-IOS-01 Phase 3 Wave E1 — shared lab-report detail VM. Port of
 * android/feature-blood/.../ReportDetailViewModel. The Android version's
 * `preparePdfIntent(...)` (FileProvider + system-viewer Intent) is Android-only
 * platform glue; on iOS the SwiftUI view fetches the bytes via [downloadPdf] and
 * hands them to QuickLook / a share sheet, so the shared VM exposes only the
 * platform-neutral [downloadPdf] + [delete] intents. The [reportId] is passed in
 * by the platform nav (SavedStateHandle on Android, route value on iOS).
 */
class ReportDetailViewModel(
    private val reports: BloodTestReportRepository,
    private val reportId: String,
) : ViewModel() {

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val report: BloodTestReport) : UiState
        data class Error(val message: String) : UiState
    }

    val state: StateFlow<UiState> = flow { emit(reports.get(reportId)) }
        .map { UiState.Ready(it) as UiState }
        .catch { emit(UiState.Error(it.message ?: "Failed to load report")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /**
     * Fetch the report PDF bytes. Platform-neutral (no FileProvider/Intent): the
     * SwiftUI caller writes these to a temp URL and previews via QuickLook, the
     * Android caller through the FileProvider Intent. Runs network/IO on the
     * calling coroutine — invoke from a background context.
     */
    suspend fun downloadPdf(report: BloodTestReport): ByteArray =
        reports.downloadPdf(report.pdfDownloadPath)

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { reports.delete(reportId) }
                .onSuccess { onDeleted() }
        }
    }
}
