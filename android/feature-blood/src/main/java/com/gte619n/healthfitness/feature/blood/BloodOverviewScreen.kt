package com.gte619n.healthfitness.feature.blood

import androidx.compose.runtime.getValue

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.blood.BloodMarker
import com.gte619n.healthfitness.domain.blood.BloodTestReport
import com.gte619n.healthfitness.feature.blood.components.MarkerCard
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.format.DateTimeFormatter

private val reportDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

@Composable
fun BloodOverviewScreen(
    onBack: () -> Unit,
    onMarkerClick: (BloodMarker) -> Unit,
    onReportClick: (String) -> Unit,
    onAddReading: () -> Unit,
    onUploadPdf: () -> Unit,
    viewModel: BloodOverviewViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    when (val s = ui) {
        BloodOverviewViewModel.UiState.Loading -> LoadingState()
        is BloodOverviewViewModel.UiState.Error -> ErrorState(message = s.message, onRetry = viewModel::retry)
        is BloodOverviewViewModel.UiState.Ready -> BloodOverviewContent(
            state = s,
            onBack = onBack,
            onMarkerClick = onMarkerClick,
            onReportClick = onReportClick,
            onAddReading = onAddReading,
            onUploadPdf = onUploadPdf,
        )
    }
}

@Composable
private fun BloodOverviewContent(
    state: BloodOverviewViewModel.UiState.Ready,
    onBack: () -> Unit,
    onMarkerClick: (BloodMarker) -> Unit,
    onReportClick: (String) -> Unit,
    onAddReading: () -> Unit,
    onUploadPdf: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                )
            }
            SectionTitle(text = "Blood")
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = onAddReading, modifier = Modifier.weight(1f)) { Text("Add reading") }
                    OutlinedButton(onClick = onUploadPdf, modifier = Modifier.weight(1f)) { Text("Upload lab PDF") }
                }
            }

            item { SectionTitle("Tracked markers") }
            items(state.trackedMarkers, key = { it.marker.name }) { latest ->
                MarkerCard(latest = latest, onClick = { onMarkerClick(latest.marker) })
            }

            item {
                Spacer(Modifier.height(4.dp))
                SectionTitle("Recent reports")
            }
            if (state.recentReports.isEmpty()) {
                item {
                    Text(
                        text = "No lab reports yet. Upload a lab PDF to get started.",
                        style = Hf.type.bodyMd,
                        color = Hf.colors.textTertiary,
                    )
                }
            } else {
                items(state.recentReports, key = { it.reportId }) { report ->
                    ReportRow(report = report, onClick = { onReportClick(report.reportId) })
                }
            }
        }
    }
}

@Composable
private fun ReportRow(report: BloodTestReport, onClick: () -> Unit) {
    HfCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Text(text = report.labSource.ifBlank { "Lab report" }, style = Hf.type.bodyLg, color = Hf.colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(report.sampleDate?.format(reportDateFmt) ?: "Date unknown")
                    append(" · ")
                    append("${report.markers.size} markers")
                },
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
        }
    }
}
