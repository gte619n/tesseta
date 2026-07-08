package com.gte619n.healthfitness.feature.workouts.program

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.feature.workouts.GymsListScreen
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * The Workouts hub — a tabbed shell (IMPL-AND-15 redesign). The default
 * "This Week" tab is the active-program dashboard ([WorkoutsLandingRoute]); the
 * Programs / History / Gyms tabs render their existing screens inline with their
 * own headers suppressed (the shell owns the one header + tab row). The program
 * builder no longer lives here — it's the sparkle on the Programs tab and the
 * refine action inside a program.
 */
enum class WorkoutsTab(val label: String) {
    THIS_WEEK("This Week"),
    PROGRAMS("Programs"),
    HISTORY("History"),
    GYMS("Gyms"),
}

@Composable
fun WorkoutsHubRoute(
    onBack: () -> Unit,
    onOpenProgram: (programId: String) -> Unit,
    onOpenGym: (locationId: String) -> Unit,
    onAddGym: () -> Unit,
    onOpenWorkout: (programId: String, phaseId: String, dayId: String) -> Unit,
    onOpenSession: (programId: String, scheduledId: String) -> Unit,
    onDesignProgram: (programId: String?) -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(WorkoutsTab.THIS_WEEK) }

    WorkoutsHubScreen(
        selectedTab = selectedTab,
        onSelectTab = { selectedTab = it },
        onBack = onBack,
        onDesignNew = { onDesignProgram(null) },
        landingContent = {
            WorkoutsLandingRoute(
                onOpenSession = onOpenSession,
                onOpenWorkout = onOpenWorkout,
                onRefine = onDesignProgram,
                onOpenProgramsTab = { selectedTab = WorkoutsTab.PROGRAMS },
            )
        },
        programsContent = {
            ProgramsListRoute(
                onBack = {},
                onOpenProgram = onOpenProgram,
                onDesignProgram = { onDesignProgram(null) },
                showHeader = false,
            )
        },
        historyContent = { WorkoutHistoryRoute(onBack = {}, showHeader = false) },
        gymsContent = {
            GymsListScreen(
                onBack = {},
                onAddGym = onAddGym,
                onOpenGym = onOpenGym,
                showHeader = false,
            )
        },
    )
}

@Composable
fun WorkoutsHubScreen(
    selectedTab: WorkoutsTab,
    onSelectTab: (WorkoutsTab) -> Unit,
    onBack: () -> Unit,
    onDesignNew: () -> Unit = {},
    landingContent: @Composable () -> Unit = {},
    programsContent: @Composable () -> Unit = {},
    historyContent: @Composable () -> Unit = {},
    gymsContent: @Composable () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = "Workouts",
            subtitle = "Your training",
            onBack = onBack,
            // The builder lives with the programs — a "design a new program"
            // sparkle appears while the Programs tab is selected.
            trailing = if (selectedTab == WorkoutsTab.PROGRAMS) {
                {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clickable { onDesignNew() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = "Design a program",
                            tint = Hf.colors.accent,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            } else {
                null
            },
        )
        WorkoutsTabRow(selected = selectedTab, onSelect = onSelectTab)
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                WorkoutsTab.THIS_WEEK -> landingContent()
                WorkoutsTab.PROGRAMS -> programsContent()
                WorkoutsTab.HISTORY -> historyContent()
                WorkoutsTab.GYMS -> gymsContent()
            }
        }
    }
}

/** Segmented chips for the hub tabs (the app's custom tab idiom, cf. Medications). */
@Composable
private fun WorkoutsTabRow(
    selected: WorkoutsTab,
    onSelect: (WorkoutsTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WorkoutsTab.entries.forEach { tab ->
            val active = tab == selected
            Box(
                modifier = Modifier
                    .border(
                        0.5.dp,
                        if (active) Hf.colors.accent else Hf.colors.borderDefault,
                        RoundedCornerShape(8.dp),
                    )
                    .background(
                        if (active) Hf.colors.accentBg else Hf.colors.surface,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            ) {
                Text(
                    tab.label,
                    style = Hf.type.bodyMd.copy(
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = if (active) Hf.colors.accentDim else Hf.colors.textSecondary,
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun WorkoutsHubPreview() {
    HealthFitnessTheme {
        WorkoutsHubScreen(
            selectedTab = WorkoutsTab.THIS_WEEK,
            onSelectTab = {},
            onBack = {},
        )
    }
}
