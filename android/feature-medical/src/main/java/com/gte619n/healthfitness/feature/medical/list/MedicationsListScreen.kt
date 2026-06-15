package com.gte619n.healthfitness.feature.medical.list

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.medications.Medication
import com.gte619n.healthfitness.feature.medical.components.MedicationGrid
import com.gte619n.healthfitness.feature.medical.reminders.ReminderPermissionWarning
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun MedicationsListScreen(
    onAdd: () -> Unit,
    onMedicationClick: (medicationId: String) -> Unit,
    onOpenReminders: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: MedicationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tab by viewModel.tab.collectAsStateWithLifecycle()

    // Reload whenever the list returns to the foreground (e.g. after popping back
    // from Add or Detail) so newly added / changed medications appear.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val widthSizeClass = currentWidthSizeClass()

    Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).background(Hf.colors.canvas)) {
        Column(modifier = Modifier.fillMaxSize()) {
            HfScreenHeader(
                title = "Medications",
                subtitle = "Your current and past medications",
                onBack = onBack,
            )
            TabRow(active = tab, onTabChange = viewModel::setTab, onOpenReminders = onOpenReminders)
            Spacer(Modifier.height(4.dp))

            when (val s = state) {
                is MedicationsUiState.Loading -> LoadingState(label = "Loading medications…")
                is MedicationsUiState.Error -> ErrorState(message = s.message, onRetry = viewModel::refresh)
                is MedicationsUiState.Ready -> {
                    val meds = when (tab) {
                        MedicationsTab.CURRENT -> s.active
                        MedicationsTab.HISTORY -> s.discontinued
                    }
                    // IMPL-STAB Workstream F (item 3): persistent "reminders won't
                    // fire" warning when a required permission is missing — shown
                    // only when the user actually has active meds to remind about.
                    if (s.active.isNotEmpty()) {
                        ReminderPermissionWarning(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 4.dp),
                        )
                    }
                    if (meds.isEmpty()) {
                        EmptyState(
                            title = emptyTitle(tab),
                            description = emptyDescription(tab),
                        )
                    } else {
                        MedicationGrid(
                            medications = meds,
                            widthSizeClass = widthSizeClass,
                            onMedicationClick = onMedicationClick,
                        )
                    }
                }
            }
        }

        // FAB to add a medication.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(52.dp)
                .background(Hf.colors.accent, CircleShape)
                .clickable { onAdd() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.Add,
                contentDescription = "Add medication",
                tint = Hf.colors.textInverse,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

@Composable
private fun TabRow(
    active: MedicationsTab,
    onTabChange: (MedicationsTab) -> Unit,
    onOpenReminders: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MedicationsTab.entries.forEach { entry ->
            val isActive = entry == active
            Box(
                modifier = Modifier
                    .background(
                        if (isActive) Hf.colors.accentBg else Hf.colors.surface,
                        RoundedCornerShape(7.dp),
                    )
                    .border(
                        0.5.dp,
                        if (isActive) Hf.colors.accent else Hf.colors.borderDefault,
                        RoundedCornerShape(7.dp),
                    )
                    .clickable { onTabChange(entry) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    entry.label,
                    style = Hf.type.bodyMd.copy(fontSize = 12.sp),
                    color = if (isActive) Hf.colors.accentDim else Hf.colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        // Dose-reminder settings (IMPL-16 Part A).
        Box(
            modifier = Modifier
                .background(Hf.colors.surface, RoundedCornerShape(7.dp))
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(7.dp))
                .clickable { onOpenReminders() }
                .padding(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Notifications,
                contentDescription = "Dose reminders",
                tint = Hf.colors.textSecondary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

private fun emptyTitle(tab: MedicationsTab): String = when (tab) {
    MedicationsTab.CURRENT -> "No current medications"
    MedicationsTab.HISTORY -> "No past medications"
}

private fun emptyDescription(tab: MedicationsTab): String = when (tab) {
    MedicationsTab.CURRENT -> "Tap + to add a medication."
    MedicationsTab.HISTORY -> "Discontinued medications will appear here."
}

/**
 * Derive a [WindowWidthSizeClass] from the current screen width. Avoids
 * needing an Activity reference for `calculateWindowSizeClass`.
 */
@Composable
private fun currentWidthSizeClass(): WindowWidthSizeClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> WindowWidthSizeClass.Compact
        widthDp < 840 -> WindowWidthSizeClass.Medium
        else -> WindowWidthSizeClass.Expanded
    }
}

@Suppress("unused")
private fun previewMeds(): List<Medication> = emptyList()
