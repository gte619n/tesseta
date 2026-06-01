package com.gte619n.healthfitness.feature.blood

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.feature.blood.components.ExtractedMarkerRow
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val dateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

@Composable
fun ReportDetailScreen(
    onBack: () -> Unit,
    viewModel: ReportDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var pdfError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(
            title = "Report",
            subtitle = "Lab report details",
            onBack = onBack,
        )
        when (val s = ui) {
            ReportDetailViewModel.UiState.Loading -> LoadingState()
            is ReportDetailViewModel.UiState.Error -> ErrorState(message = s.message, onRetry = onBack)
            is ReportDetailViewModel.UiState.Ready -> {
                val report = s.report
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { ReportHeader(report) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            val intent: Intent = viewModel.preparePdfIntent(report)
                                            context.startActivity(intent)
                                        }.onFailure { e ->
                                            pdfError = e.localizedMessage ?: "Couldn't open PDF"
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("View PDF") }
                            OutlinedButton(
                                onClick = { confirmDelete = true },
                                modifier = Modifier.weight(1f),
                            ) { Text("Delete report") }
                        }
                    }
                    pdfError?.let { err ->
                        item { Text(err, style = Hf.type.bodySm, color = Hf.colors.alert) }
                    }

                    item { SectionTitle("Extracted markers") }
                    itemsIndexed(report.markers) { _, marker ->
                        ExtractedMarkerRow(marker = marker)
                    }
                }

                if (confirmDelete) {
                    ConfirmDialog(
                        title = "Delete report?",
                        message = "This permanently removes the report and its extracted markers.",
                        confirmLabel = "Delete",
                        onConfirm = {
                            confirmDelete = false
                            viewModel.delete(onDeleted = onBack)
                        },
                        onDismiss = { confirmDelete = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportHeader(report: BloodTestReport) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(report.labSource.ifBlank { "Lab report" }, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
            Spacer(Modifier.height(4.dp))
            Text(
                text = report.sampleDate?.format(dateFmt) ?: "Sample date unknown",
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
    }
}
