package com.gte619n.healthfitness.feature.medical.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.DosagePeriod
import com.gte619n.healthfitness.domain.medications.DoseFormatter
import com.gte619n.healthfitness.domain.medications.MedicationHistoryEntry
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
internal fun DosingTimeline(periods: List<DosagePeriod>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        periods.sortedByDescending { it.startDate }.forEach { period ->
            HfCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            DoseFormatter.format(period.dose, period.unit),
                            style = Hf.type.headingSm,
                            color = Hf.colors.textPrimary,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            rangeLabel(period),
                            style = Hf.type.bodySm,
                            color = Hf.colors.textTertiary,
                        )
                    }
                    if (period.isActive) {
                        Pill(text = "Current", tone = HfTone.Good)
                    }
                }
            }
        }
    }
}

/**
 * Human-readable date range. End dates are exclusive on the wire ([PR#8]); we
 * subtract a day for display so a closed period reads up to its last active day.
 */
internal fun rangeLabel(period: DosagePeriod): String {
    val start = period.startDate.toString()
    val end = period.endDate
    return if (end == null) {
        "$start – Present"
    } else {
        "$start – ${end.minusDays(1)}"
    }
}

@Composable
internal fun HistoryRow(entry: MedicationHistoryEntry) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                entry.changeType.name.replace('_', ' ').lowercase()
                    .replaceFirstChar { it.uppercase() },
                style = Hf.type.headingSm,
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${entry.previousValue} → ${entry.newValue}",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
            entry.notes?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
        }
    }
}

@Composable
internal fun KeyValueRow(key: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        CapsLabel(key, color = Hf.colors.textTertiary)
        Text(value, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
    }
}

@Composable
internal fun ActionButton(
    label: String,
    enabled: Boolean = true,
    tone: HfTone = HfTone.Neutral,
    onClick: () -> Unit,
) {
    val fg = if (tone == HfTone.Alert) Hf.colors.alert else Hf.colors.textSecondary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Hf.type.capsSm, color = fg)
    }
}
