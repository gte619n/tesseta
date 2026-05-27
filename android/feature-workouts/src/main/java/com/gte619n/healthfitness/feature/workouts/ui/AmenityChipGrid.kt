package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.Amenity
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * 10-cell wrap-row of amenity toggles. Selected chips invert the
 * accent colour so the selection is obvious without color-coding it
 * red/green.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmenityChipGrid(
    selected: Set<Amenity>,
    onToggle: (Amenity) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Amenity.entries.forEach { amenity ->
            AmenityChip(
                label = amenity.label,
                selected = amenity in selected,
                readOnly = readOnly,
                onClick = { if (!readOnly) onToggle(amenity) },
            )
        }
    }
}

@Composable
private fun AmenityChip(
    label: String,
    selected: Boolean,
    readOnly: Boolean,
    onClick: () -> Unit,
) {
    val container = when {
        selected -> Hf.colors.accentBg
        else -> Hf.colors.surface
    }
    val text = when {
        selected -> Hf.colors.accentDim
        else -> Hf.colors.textSecondary
    }
    val border = when {
        selected -> Hf.colors.accent
        else -> Hf.colors.borderDefault
    }
    val mod = Modifier
        .background(container, RoundedCornerShape(20.dp))
        .border(0.5.dp, border, RoundedCornerShape(20.dp))
        .padding(horizontal = 12.dp, vertical = 6.dp)
        .let { if (!readOnly) it.clickable(onClick = onClick) else it }
    androidx.compose.foundation.layout.Box(modifier = mod) {
        Text(text = label, style = Hf.type.bodySm, color = text)
    }
}
