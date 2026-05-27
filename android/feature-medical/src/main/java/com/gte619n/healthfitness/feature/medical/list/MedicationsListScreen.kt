package com.gte619n.healthfitness.feature.medical.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.feature.medical.components.MedicationCard
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Top-level medications screen — Current / History tabs, FAB to add.
 *
 * Grid column count follows the window width class:
 *   Compact → 1, Medium → 2, Expanded → 3. Matches what the web client
 *   does for `grid-cols-1 md:grid-cols-2 xl:grid-cols-3`.
 */
@OptIn(androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun MedicationsListScreen(
    onAdd: () -> Unit,
    onMedicationClick: (medicationId: String) -> Unit,
    viewModel: MedicationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by remember { mutableStateOf(Tab.Current) }

    // Window width — read from the activity context. We resolve it via
    // calculateWindowSizeClass which is fine inside a feature module
    // because no @ExperimentalMaterial3WindowSizeClassApi marker reaches
    // the public Screen function signature.
    val widthClass = calculateWindowSizeClass(
        activity = LocalContext.current as androidx.activity.ComponentActivity,
    ).widthSizeClass

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Medications",
                    style = Hf.type.headingLg,
                    color = Hf.colors.textPrimary,
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TabPill(
                    label = "Current",
                    selected = selectedTab == Tab.Current,
                    onClick = { selectedTab = Tab.Current },
                )
                TabPill(
                    label = "History",
                    selected = selectedTab == Tab.History,
                    onClick = { selectedTab = Tab.History },
                )
            }
            Spacer(Modifier.height(12.dp))
            when (val s = state) {
                MedicationsUiState.Loading -> LoadingState(label = "Loading medications...")
                is MedicationsUiState.Error -> ErrorState(
                    message = s.message,
                    onRetry = viewModel::refresh,
                )
                is MedicationsUiState.Ready -> {
                    val list = if (selectedTab == Tab.Current) s.active else s.discontinued
                    MedicationsGrid(
                        meds = list,
                        widthClass = widthClass,
                        onClick = onMedicationClick,
                    )
                }
            }
        }
        FloatingActionButton(
            onClick = onAdd,
            containerColor = Hf.colors.accent,
            contentColor = Hf.colors.textInverse,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp),
        ) {
            Icon(Icons.Outlined.Add, contentDescription = "Add medication")
        }
    }
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) Hf.colors.accentBg else Hf.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(
            text = label,
            style = Hf.type.bodyMd,
            color = if (selected) Hf.colors.accentDim else Hf.colors.textSecondary,
        )
    }
}

@Composable
private fun MedicationsGrid(
    meds: List<Medication>,
    widthClass: WindowWidthSizeClass,
    onClick: (String) -> Unit,
) {
    if (meds.isEmpty()) {
        EmptyState(
            title = "No medications yet",
            description = "Tap the + button to add your first.",
            icon = Icons.Outlined.Medication,
        )
        return
    }
    val cols = when (widthClass) {
        WindowWidthSizeClass.Compact -> 1
        WindowWidthSizeClass.Medium -> 2
        else -> 3
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(meds, key = { it.medicationId }) { med ->
            MedicationCard(
                medication = med,
                onClick = { onClick(med.medicationId) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private enum class Tab { Current, History }
