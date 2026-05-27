package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.DoseFormatter
import com.gte619n.healthfitness.domain.medications.DiscontinueReasonLabels
import com.gte619n.healthfitness.domain.medications.FrequencyFormatter
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeWindowLabels
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Card representation of a medication. Mirrors web's `MedicationCard.tsx`:
 *
 *  - 1:1 drug image with form-shaped fallback
 *  - Name + category pill
 *  - Dose + frequency line
 *  - Time-window chip row (omitted if empty)
 *  - 30-day adherence sparkline
 *  - "Discontinued" overlay when [Medication.status] is DISCONTINUED
 *
 * Click handler is required — the card is the entry to the detail screen.
 */
@Composable
fun MedicationCard(
    medication: Medication,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val discontinued = medication.status == MedicationStatus.DISCONTINUED

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Hf.colors.surface)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                DrugImage(
                    imageUrl = medication.drug?.imageUrl,
                    imageFallback = medication.drug?.imageFallback,
                    form = medication.drug?.form,
                    contentDescription = medication.displayName,
                    modifier = Modifier.fillMaxSize(),
                )
                if (discontinued) {
                    DiscontinuedOverlay(reasonLabel = medication.discontinueReason?.let(DiscontinueReasonLabels::label))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = medication.displayName,
                    style = Hf.type.headingMd,
                    color = Hf.colors.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                medication.drug?.category?.let { cat ->
                    CategoryPill(label = cat.name.lowercase().replaceFirstChar { it.uppercase() })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${DoseFormatter.format(medication.dose, medication.unit)} · ${FrequencyFormatter.format(medication.frequency)}",
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
            if (medication.timeSlots.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    medication.timeSlots.take(4).forEach { slot ->
                        TimeWindowChip(label = TimeWindowLabels.shortLabel(slot.window))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            AdherenceSparkline(adherence = medication.adherence)
        }
    }
}

@Composable
private fun CategoryPill(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Hf.colors.accentBg)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = Hf.type.capsSm,
            color = Hf.colors.accentDim,
        )
    }
}

@Composable
private fun TimeWindowChip(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Hf.colors.canvasMuted)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = Hf.type.capsSm,
            color = Hf.colors.textSecondary,
        )
    }
}

@Composable
private fun DiscontinuedOverlay(reasonLabel: String?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas.copy(alpha = 0.75f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Discontinued",
                style = Hf.type.headingSm,
                color = Hf.colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            if (reasonLabel != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = reasonLabel,
                    style = Hf.type.capsSm,
                    color = Hf.colors.textTertiary,
                )
            }
        }
    }
}
