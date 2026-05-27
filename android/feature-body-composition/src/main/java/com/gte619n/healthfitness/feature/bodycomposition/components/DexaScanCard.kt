package com.gte619n.healthfitness.feature.bodycomposition.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.bodycomposition.DexaScanSummary
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One card in the overview's "DEXA scans" grid. Tappable; routes the
 * caller to the detail screen via [onClick]. Shows the measurement date,
 * source facility, and the two headline numbers (total mass + body-fat
 * %). Renders em-dashes for missing values rather than skipping rows so
 * the grid layout stays predictable.
 */
@Composable
fun DexaScanCard(
    summary: DexaScanSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = summary.measuredOn?.let { DATE.format(it) } ?: "Unknown date",
                    style = Hf.type.bodyMd.copy(fontSize = 13.sp),
                    color = Hf.colors.textPrimary,
                )
                Text(
                    text = "View",
                    style = Hf.type.bodySm.copy(fontSize = 11.sp),
                    color = Hf.colors.accent,
                )
            }
            val facility = summary.sourceFacility
            if (!facility.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = facility,
                    style = Hf.type.bodySm.copy(fontSize = 11.sp),
                    color = Hf.colors.textTertiary,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                NumericColumn(
                    label = "Total mass",
                    value = summary.totalMassLb?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                    unit = "lb",
                )
                NumericColumn(
                    label = "Body fat",
                    value = summary.totalBodyFatPercent?.let { String.format(Locale.US, "%.1f", it) } ?: "—",
                    unit = "%",
                )
            }
        }
    }
}

@Composable
private fun NumericColumn(label: String, value: String, unit: String) {
    Column {
        SectionTitle(text = label)
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = Hf.type.displayLg.copy(fontSize = 18.sp, lineHeight = 20.sp),
                color = Hf.colors.textPrimary,
            )
            Text(
                text = " $unit",
                style = Hf.type.bodySm.copy(fontSize = 10.sp),
                color = Hf.colors.textTertiary,
            )
        }
    }
}

private val DATE: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
