package com.gte619n.healthfitness.feature.workouts.program.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.trt.DangerFlag
import com.gte619n.healthfitness.domain.workouts.trt.DangerSeverity
import com.gte619n.healthfitness.domain.workouts.trt.TrtContext
import com.gte619n.healthfitness.domain.workouts.trt.TrtMarker
import com.gte619n.healthfitness.domain.workouts.trt.TrtMarkerStatus
import com.gte619n.healthfitness.domain.workouts.trt.TrtTrend
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * The TRT/labs decision-support surface in the designer chat (IMPL-AND-18 /
 * ADR-0015). Shows the monitoring-panel markers vs. range with trend + a
 * status-colored Pill, and a mandatory danger banner when any dangerFlags fire
 * (HfTone.Alert for DANGER, Warn for WARNING). Rendered only when [TrtContext]
 * is on TRT or carries markers. The model's cited guidance shows as normal
 * assistant text in the thread — this panel is the structured lab readout.
 */
@Composable
fun TrtLabsPanel(trt: TrtContext, modifier: Modifier = Modifier) {
    if (!trt.shouldShow) return

    // Default collapsed to keep the top of the designer uncluttered; the
    // mandatory danger banners still render below regardless of this.
    var expanded by remember { mutableStateOf(false) }

    HfCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(13.dp)) {
            // Tappable header toggles the routine readout. Danger flags below stay
            // visible regardless (S6e) so a collapse can't hide a mandatory alert.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CapsLabel("TRT monitoring", color = Hf.colors.accentDim)
                Spacer(Modifier.weight(1f))
                Pill(if (trt.onTrt) "On TRT" else "Labs", tone = HfTone.Neutral)
                Spacer(Modifier.size(6.dp))
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) "Hide TRT monitoring" else "Show TRT monitoring",
                    tint = Hf.colors.textSecondary,
                    modifier = Modifier.size(20.dp),
                )
            }

            // Danger flags first — they are mandatory and shown regardless (S6e),
            // even when the panel is collapsed.
            trt.dangerFlags.forEach { flag ->
                Spacer(Modifier.height(8.dp))
                DangerBanner(flag)
            }

            if (expanded) {
                trt.markers.forEach { marker ->
                    Spacer(Modifier.height(8.dp))
                    MarkerRow(marker)
                }

                if (trt.dangerFlags.isEmpty() && trt.markers.isEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "No recent monitoring labs on file. Add bloodwork for grounded guidance.",
                        style = Hf.type.bodySm,
                        color = Hf.colors.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkerRow(marker: TrtMarker) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.canvas, RoundedCornerShape(7.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(marker.label, style = Hf.type.bodyMd, color = Hf.colors.textPrimary, maxLines = 1)
            val range = rangeLabel(marker)
            if (range != null) {
                Text(range, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
        }
        Text(
            valueLabel(marker),
            style = Hf.type.monoMd,
            color = Hf.colors.textPrimary,
        )
        Text(trendGlyph(marker.trend), style = Hf.type.bodyMd, color = Hf.colors.textSecondary)
        Pill(statusLabel(marker.status), tone = statusTone(marker.status))
    }
}

@Composable
private fun DangerBanner(flag: DangerFlag) {
    val tone = if (flag.severity == DangerSeverity.DANGER) HfTone.Alert else HfTone.Warn
    val (bg, fg) = when (tone) {
        HfTone.Alert -> Hf.colors.alertBg to Hf.colors.alert
        else -> Hf.colors.warnBg to Hf.colors.warn
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(7.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CapsLabel(
                if (flag.severity == DangerSeverity.DANGER) "Danger" else "Warning",
                color = fg,
                size = 9,
            )
            if (flag.marker.isNotBlank()) {
                Text(flag.marker, style = Hf.type.bodySm, color = fg)
            }
        }
        Text(flag.message, style = Hf.type.bodySm, color = fg)
    }
}

// ---- formatting ----

private fun valueLabel(m: TrtMarker): String {
    val v = m.value ?: return "—"
    val num = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
    return m.unit?.let { "$num $it" } ?: num
}

private fun rangeLabel(m: TrtMarker): String? {
    val low = m.refLow
    val high = m.refHigh
    return when {
        low != null && high != null -> "ref ${trim(low)}–${trim(high)}"
        low != null -> "ref ≥ ${trim(low)}"
        high != null -> "ref ≤ ${trim(high)}"
        else -> null
    }
}

private fun trim(v: Double): String = if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

private fun trendGlyph(t: TrtTrend): String = when (t) {
    TrtTrend.RISING -> "↑"
    TrtTrend.FALLING -> "↓"
    TrtTrend.STABLE -> "→"
    TrtTrend.UNKNOWN -> ""
}

private fun statusLabel(s: TrtMarkerStatus): String = when (s) {
    TrtMarkerStatus.LOW -> "Low"
    TrtMarkerStatus.IN_RANGE -> "In range"
    TrtMarkerStatus.HIGH -> "High"
    TrtMarkerStatus.WATCH -> "Watch"
    TrtMarkerStatus.UNKNOWN -> "—"
}

private fun statusTone(s: TrtMarkerStatus): HfTone = when (s) {
    TrtMarkerStatus.IN_RANGE -> HfTone.Good
    TrtMarkerStatus.WATCH -> HfTone.Warn
    TrtMarkerStatus.HIGH, TrtMarkerStatus.LOW -> HfTone.Alert
    TrtMarkerStatus.UNKNOWN -> HfTone.Neutral
}
