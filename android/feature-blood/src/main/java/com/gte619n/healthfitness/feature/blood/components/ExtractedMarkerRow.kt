package com.gte619n.healthfitness.feature.blood.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.blood.ExtractedMarker
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/** One extracted-marker row on the report detail: name, value+unit, optional flag pill. */
@Composable
fun ExtractedMarkerRow(
    marker: ExtractedMarker,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = marker.name, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            val refText = buildRefText(marker)
            if (refText != null) {
                Text(text = refText, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
        }

        Text(
            text = buildString {
                append(marker.value?.let { formatValue(it) } ?: "—")
                marker.unit?.takeIf { it.isNotBlank() }?.let { append(" $it") }
            },
            style = Hf.type.monoMd,
            color = Hf.colors.textPrimary,
        )

        when (marker.flag) {
            ExtractedMarker.Flag.H -> Pill(text = "HIGH", tone = HfTone.Alert)
            ExtractedMarker.Flag.L -> Pill(text = "LOW", tone = HfTone.Warn)
            null -> Unit
        }
    }
}

private fun buildRefText(marker: ExtractedMarker): String? {
    val lo = marker.refRangeLow
    val hi = marker.refRangeHigh
    return when {
        lo != null && hi != null -> "Ref ${formatValue(lo)}–${formatValue(hi)}"
        hi != null -> "Ref < ${formatValue(hi)}"
        lo != null -> "Ref > ${formatValue(lo)}"
        else -> null
    }
}

private fun formatValue(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else String.format("%.1f", v)
