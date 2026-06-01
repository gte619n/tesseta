package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.Amenity

/** A flow row of FilterChips for the 10 amenities. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AmenityChipGrid(
    selected: Set<Amenity>,
    onToggle: (Amenity) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Amenity.entries.forEach { amenity ->
            val isSelected = amenity in selected
            FilterChip(
                selected = isSelected,
                onClick = { if (enabled) onToggle(amenity) },
                enabled = enabled,
                label = { Text(amenity.label) },
            )
        }
    }
}
