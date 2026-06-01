package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.DoseFormatter
import com.gte619n.healthfitness.domain.medications.DrugCategory
import com.gte619n.healthfitness.domain.medications.FrequencyFormatter
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.domain.medications.MedicationStatus
import com.gte619n.healthfitness.domain.medications.TimeWindowLabels
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfTone
import com.gte619n.healthfitness.ui.components.Pill
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Medications grid card mirroring web's `MedicationCard.tsx`: square drug
 * image, name + category pill, dose + frequency, time-window labels, 30-day
 * adherence sparkline, and a "Discontinued" overlay when applicable.
 */
@Composable
fun MedicationCard(
    medication: Medication,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val discontinued = medication.status == MedicationStatus.DISCONTINUED
    HfCard(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Box {
            Column(modifier = Modifier.padding(12.dp)) {
                // Square drug image.
                DrugImage(
                    drug = medication.drug,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .then(if (discontinued) Modifier.alpha(0.5f) else Modifier),
                )
                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top,
                ) {
                    androidx.compose.material3.Text(
                        text = medication.displayName,
                        style = Hf.type.headingSm,
                        color = Hf.colors.textPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    medication.drug?.category?.let { category ->
                        Spacer(Modifier.height(0.dp))
                        Pill(text = categoryLabel(category), tone = HfTone.Neutral)
                    }
                }

                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.Text(
                    text = "${DoseFormatter.format(medication.dose, medication.unit)} · " +
                        FrequencyFormatter.format(medication.frequency),
                    style = Hf.type.bodySm,
                    color = Hf.colors.textSecondary,
                )

                if (medication.timeSlots.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        medication.timeSlots.take(4).forEach { slot ->
                            Pill(text = TimeWindowLabels.label(slot.window), tone = HfTone.Good)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                AdherenceSparkline(adherence = medication.adherence, modifier = Modifier.fillMaxWidth())
            }

            if (discontinued) {
                Box(
                    modifier = Modifier
                        .padding(12.dp)
                        .align(Alignment.TopStart)
                        .background(Hf.colors.alertBg, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    androidx.compose.material3.Text(
                        text = "Discontinued",
                        style = Hf.type.capsSm,
                        color = Hf.colors.alert,
                    )
                }
            }
        }
    }
}

internal fun categoryLabel(category: DrugCategory): String = when (category) {
    DrugCategory.PRESCRIPTION -> "Rx"
    DrugCategory.SUPPLEMENT -> "Supplement"
    DrugCategory.OTC -> "OTC"
    DrugCategory.PEPTIDE -> "Peptide"
    DrugCategory.TOPICAL -> "Topical"
}
