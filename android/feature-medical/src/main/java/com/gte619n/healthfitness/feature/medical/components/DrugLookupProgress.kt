package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.DrugLookupEvent
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Live phase indicator for the SSE drug-lookup stream. Renders a small
 * spinner + the rotating message from the backend (`Looking up drug
 * information...`, `Generating image...`, etc).
 */
@Composable
fun DrugLookupProgress(event: DrugLookupEvent?, modifier: Modifier = Modifier) {
    if (event !is DrugLookupEvent.Progress) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(
            color = Hf.colors.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(14.dp),
        )
        Column {
            Text(
                text = humanizePhase(event.phase),
                style = Hf.type.capsSm,
                color = Hf.colors.textTertiary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = event.message ?: humanizePhase(event.phase),
                style = Hf.type.bodySm,
                color = Hf.colors.textSecondary,
            )
        }
    }
}

private fun humanizePhase(phase: String): String = when (phase) {
    "searching" -> "SEARCHING"
    "generating_image" -> "GENERATING IMAGE"
    "found" -> "FOUND"
    else -> phase.replace('_', ' ').uppercase()
}
