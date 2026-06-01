package com.gte619n.healthfitness.feature.medical.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.Medication

/**
 * Responsive card grid: 1 column on phones (Compact), 2 on Medium, 3 on
 * Expanded (unfolded foldable inner display).
 */
@Composable
fun MedicationGrid(
    medications: List<Medication>,
    widthSizeClass: WindowWidthSizeClass,
    onMedicationClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = when (widthSizeClass) {
        WindowWidthSizeClass.Compact -> 1
        WindowWidthSizeClass.Medium -> 2
        else -> 3
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(medications, key = { it.medicationId }) { medication ->
            MedicationCard(
                medication = medication,
                onClick = { onMedicationClick(medication.medicationId) },
            )
        }
    }
}
