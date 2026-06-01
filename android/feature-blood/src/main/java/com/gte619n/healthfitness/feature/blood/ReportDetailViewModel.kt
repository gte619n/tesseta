package com.gte619n.healthfitness.feature.blood

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.domain.blood.BloodTestReportRepository
import com.gte619n.healthfitness.feature.blood.nav.BloodRoutes
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ReportDetailViewModel @Inject constructor(
    private val reports: BloodTestReportRepository,
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
) : ViewModel() {

    private val reportId: String = requireNotNull(savedState.get<String>(BloodRoutes.ARG_REPORT_ID)) {
        "Missing ${BloodRoutes.ARG_REPORT_ID} route arg"
    }

    sealed interface UiState {
        data object Loading : UiState
        data class Ready(val report: BloodTestReport) : UiState
        data class Error(val message: String) : UiState
    }

    val state: StateFlow<UiState> = flow { emit(reports.get(reportId)) }
        .map { UiState.Ready(it) as UiState }
        .catch { emit(UiState.Error(it.localizedMessage ?: "Failed to load report")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /**
     * Downloads the PDF into `cacheDir/blood/{reportId}.pdf`, exposes it via the
     * app's FileProvider, and returns an [Intent] ready to launch the system
     * PDF viewer. Runs network/IO on the calling coroutine — invoke from a
     * background dispatcher / suspend context.
     */
    suspend fun preparePdfIntent(report: BloodTestReport): Intent {
        val bytes = reports.downloadPdf(report.pdfDownloadPath)
        val dir = File(context.cacheDir, "blood").apply { mkdirs() }
        val file = File(dir, "${report.reportId}.pdf")
        file.writeBytes(bytes)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/pdf")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            runCatching { reports.delete(reportId) }
                .onSuccess { onDeleted() }
        }
    }
}
