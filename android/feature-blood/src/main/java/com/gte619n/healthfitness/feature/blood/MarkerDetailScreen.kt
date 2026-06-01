package com.gte619n.healthfitness.feature.blood

import androidx.compose.runtime.getValue

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.blood.MarkerCatalog
import com.gte619n.healthfitness.feature.blood.components.MarkerHistoryChart
import com.gte619n.healthfitness.feature.blood.components.MarkerReferenceBar
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.format.DateTimeFormatter

private val rowDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

@Composable
fun MarkerDetailScreen(
    onBack: () -> Unit,
    viewModel: MarkerDetailViewModel = hiltViewModel(),
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(
            title = MarkerCatalog.displayName(viewModel.marker),
            subtitle = "Blood marker history",
            onBack = onBack,
        )
        when (val s = ui) {
            MarkerDetailViewModel.UiState.Loading -> LoadingState()
            is MarkerDetailViewModel.UiState.Error -> ErrorState(message = s.message, onRetry = onBack)
            is MarkerDetailViewModel.UiState.Ready -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    HfCard(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
                            Text(
                                text = MarkerCatalog.description(viewModel.marker),
                                style = Hf.type.bodyMd,
                                color = Hf.colors.textSecondary,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Target: ${MarkerCatalog.target(viewModel.marker)}",
                                style = Hf.type.bodySm,
                                color = Hf.colors.textTertiary,
                            )
                            s.latest.reference?.let { ref ->
                                Spacer(Modifier.height(12.dp))
                                MarkerReferenceBar(value = s.latest.value, reference = ref)
                            }
                        }
                    }
                }

                item {
                    MarkerHistoryChart(history = s.history, reference = s.latest.reference)
                }

                item { SectionTitle("Readings") }
                if (s.rows.isEmpty()) {
                    item {
                        Text(
                            text = "No readings recorded for this marker.",
                            style = Hf.type.bodyMd,
                            color = Hf.colors.textTertiary,
                        )
                    }
                } else {
                    items(s.rows, key = { "${it.date}-${it.value}" }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.date.format(rowDateFmt), style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
                                Text(row.sourceLabel, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                            }
                            Text(
                                text = buildString {
                                    append(formatValue(row.value))
                                    if (row.unit.isNotBlank()) append(" ${row.unit}")
                                },
                                style = Hf.type.monoMd,
                                color = Hf.colors.textPrimary,
                            )
                        }
                        HorizontalDivider(color = Hf.colors.borderSubtle)
                    }
                }
            }
        }
    }
}

private fun formatValue(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
