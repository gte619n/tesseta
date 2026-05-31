package com.gte619n.healthfitness.mobile.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.dashboard.BloodMarkerSummary
import com.gte619n.healthfitness.domain.dashboard.MarkerTone
import com.gte619n.healthfitness.ui.components.MarkerReferenceBar
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun BloodPanel(
    markers: List<BloodMarkerSummary>,
    sampleDate: String?,
    showRangeLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Blood panel")
                if (sampleDate != null) {
                    Text(
                        text = sampleDate,
                        style = Hf.type.monoSm.copy(fontSize = 9.sp),
                        color = Hf.colors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            if (markers.isEmpty()) {
                Text(
                    text = "No blood markers yet",
                    style = Hf.type.bodySm.copy(fontSize = 11.sp),
                    color = Hf.colors.textTertiary,
                )
            } else {
                markers.forEachIndexed { i, m ->
                    MarkerRow(marker = m, showLabels = showRangeLabels)
                    if (i != markers.size - 1) Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun MarkerRow(
    marker: BloodMarkerSummary,
    showLabels: Boolean,
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = marker.displayName,
                style = Hf.type.bodyMd.copy(fontSize = 11.sp),
                color = Hf.colors.textPrimary,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatValue(marker.value),
                    style = Hf.type.monoMd.copy(fontSize = 12.sp),
                    color = when (marker.tone) {
                        MarkerTone.Warn -> Hf.colors.warn
                        MarkerTone.Alert -> Hf.colors.alert
                        MarkerTone.Good -> Hf.colors.good
                    },
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = marker.unit,
                    style = Hf.type.bodySm.copy(fontSize = 9.sp),
                    color = Hf.colors.textTertiary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        MarkerReferenceBar(
            goodLeftPct = marker.goodLeftPct,
            goodFillPct = marker.goodFillPct,
            tickPct = marker.tickPct,
        )
        if (showLabels) {
            Spacer(Modifier.height(3.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatValue(marker.displayMin), style = Hf.type.monoSm.copy(fontSize = 9.sp), color = Hf.colors.textQuaternary)
                Text(formatValue(marker.goodThreshold), style = Hf.type.monoSm.copy(fontSize = 9.sp), color = Hf.colors.good)
                Text(formatValue(marker.displayMax), style = Hf.type.monoSm.copy(fontSize = 9.sp), color = Hf.colors.textQuaternary)
            }
        }
    }
}

private fun formatValue(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
