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
 * Renders the live SSE drug-lookup phase: spinner + a friendly rotating
 * message ("Searching…", "Found …", "Generating image…").
 */
@Composable
fun DrugLookupProgress(
    event: DrugLookupEvent.Progress,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            color = Hf.colors.accent,
            strokeWidth = 2.dp,
            modifier = Modifier.size(18.dp),
        )
        Column {
            Text(
                text = event.message ?: phaseLabel(event.phase),
                style = Hf.type.bodyMd,
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "AI-assisted lookup",
                style = Hf.type.capsSm,
                color = Hf.colors.textTertiary,
            )
        }
    }
}

private fun phaseLabel(phase: String): String = when (phase.lowercase()) {
    "searching" -> "Searching…"
    "found" -> "Found a match…"
    "generating_image" -> "Generating image…"
    else -> "Working…"
}
