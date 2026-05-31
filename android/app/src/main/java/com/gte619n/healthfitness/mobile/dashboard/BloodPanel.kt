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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.domain.blood.ExtractedMarker
import com.gte619n.healthfitness.domain.blood.LatestMarker
import com.gte619n.healthfitness.domain.blood.MarkerCatalog
import com.gte619n.healthfitness.domain.blood.ReferenceRange
import com.gte619n.healthfitness.ui.components.MarkerReferenceBar
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.format.DateTimeFormatter

private val sampleDateFmt = DateTimeFormatter.ofPattern("MMM d, yyyy")

/**
 * Dashboard "Blood panel" chip. Fed [LatestMarker]s derived by
 * [com.gte619n.healthfitness.domain.blood.LatestMarkers], which merge manual
 * readings *and* uploaded lab-report markers — so a freshly uploaded panel
 * shows up here, and the chip stays in sync with the Blood overview grid.
 *
 * Lab-sourced markers carry no server reference range, so (like the overview's
 * MarkerCard) the reference bar is omitted for them and the value is coloured by
 * the lab's H/L flag instead.
 */
@Composable
fun BloodPanel(
    markers: List<LatestMarker>,
    showRangeLabels: Boolean,
    modifier: Modifier = Modifier,
) {
    val shown = markers.filter { it.value != null }
    // Header date = the most recent sample across the shown markers, so the chip
    // visibly reflects the latest panel.
    val latestSampleDate = shown.mapNotNull { it.sampleDate }.maxOrNull()
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("Blood panel")
                if (latestSampleDate != null) {
                    Text(
                        text = latestSampleDate.format(sampleDateFmt),
                        style = Hf.type.monoSm.copy(fontSize = 9.sp),
                        color = Hf.colors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(11.dp))
            if (shown.isEmpty()) {
                Text(
                    text = "No blood markers yet",
                    style = Hf.type.bodySm.copy(fontSize = 11.sp),
                    color = Hf.colors.textTertiary,
                )
            } else {
                shown.forEachIndexed { i, m ->
                    MarkerRow(marker = m, showLabels = showRangeLabels)
                    if (i != shown.size - 1) Spacer(Modifier.height(10.dp))
                }
            }
        }
    }
}

@Composable
private fun MarkerRow(
    marker: LatestMarker,
    showLabels: Boolean,
) {
    val value = marker.value ?: return
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = MarkerCatalog.displayName(marker.marker),
                style = Hf.type.bodyMd.copy(fontSize = 11.sp),
                color = Hf.colors.textPrimary,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatValue(value),
                    style = Hf.type.monoMd.copy(fontSize = 12.sp),
                    color = valueColor(marker),
                )
                if (marker.unit.isNotBlank()) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = marker.unit,
                        style = Hf.type.bodySm.copy(fontSize = 9.sp),
                        color = Hf.colors.textTertiary,
                    )
                }
            }
        }
        // Manual readings ride a server-authoritative reference range; lab-report
        // markers don't, so they show the value (flag-coloured) without a bar.
        marker.reference?.let { ref ->
            val geo = barGeometry(value, ref)
            Spacer(Modifier.height(4.dp))
            MarkerReferenceBar(
                goodLeftPct = geo.goodLeftPct,
                goodFillPct = geo.goodFillPct,
                tickPct = geo.tickPct,
            )
            if (showLabels) {
                Spacer(Modifier.height(3.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatValue(ref.displayMin), style = Hf.type.monoSm.copy(fontSize = 9.sp), color = Hf.colors.textQuaternary)
                    Text(formatValue(ref.goodThreshold), style = Hf.type.monoSm.copy(fontSize = 9.sp), color = Hf.colors.good)
                    Text(formatValue(ref.displayMax), style = Hf.type.monoSm.copy(fontSize = 9.sp), color = Hf.colors.textQuaternary)
                }
            }
        }
    }
}

private data class BarGeometry(val goodLeftPct: Float, val goodFillPct: Float, val tickPct: Float)

/** Reference-bar geometry from a range — same math the dashboard mapper used. */
private fun barGeometry(value: Double, ref: ReferenceRange): BarGeometry {
    val span = (ref.displayMax - ref.displayMin).takeIf { it != 0.0 } ?: 1.0
    val lowerIsBetter = ref.orientation == ReferenceRange.Orientation.LOWER_IS_BETTER
    val tickPct = (((value - ref.displayMin) / span).toFloat()).coerceIn(0f, 1f)
    val goodLeftPct = if (lowerIsBetter) 0f else (((ref.goodThreshold - ref.displayMin) / span).toFloat()).coerceIn(0f, 1f)
    val goodFillPct = if (lowerIsBetter) {
        (((ref.goodThreshold - ref.displayMin) / span).toFloat()).coerceIn(0f, 1f)
    } else {
        1f - goodLeftPct
    }
    return BarGeometry(goodLeftPct = goodLeftPct, goodFillPct = goodFillPct, tickPct = tickPct)
}

@Composable
private fun valueColor(marker: LatestMarker): Color {
    val value = marker.value ?: return Hf.colors.textTertiary
    if (marker.flag == ExtractedMarker.Flag.H || marker.flag == ExtractedMarker.Flag.L) {
        return Hf.colors.alert
    }
    val ref = marker.reference ?: return Hf.colors.textPrimary
    val good = when (ref.orientation) {
        ReferenceRange.Orientation.LOWER_IS_BETTER -> value <= ref.goodThreshold
        ReferenceRange.Orientation.HIGHER_IS_BETTER -> value >= ref.goodThreshold
    }
    return if (good) Hf.colors.good else Hf.colors.alert
}

private fun formatValue(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
