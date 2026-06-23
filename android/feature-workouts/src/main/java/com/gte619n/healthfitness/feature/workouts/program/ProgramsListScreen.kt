package com.gte619n.healthfitness.feature.workouts.program

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.program.ProgramStatus
import com.gte619n.healthfitness.feature.workouts.program.ui.ProgramCard
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf

@Composable
fun ProgramsListRoute(
    onBack: () -> Unit,
    onOpenProgram: (String) -> Unit,
    onDesignProgram: () -> Unit = {},
    viewModel: ProgramsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProgramsListScreen(
        state = state,
        onBack = onBack,
        onOpenProgram = onOpenProgram,
        onDesignProgram = onDesignProgram,
        onRetry = viewModel::refresh,
    )
}

@Composable
fun ProgramsListScreen(
    state: ProgramsListUiState,
    onBack: () -> Unit,
    onOpenProgram: (String) -> Unit,
    onRetry: () -> Unit,
    onDesignProgram: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = "Programs",
            subtitle = "Your training roadmap",
            onBack = onBack,
            trailing = {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clickable { onDesignProgram() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = "Design a program",
                        tint = Hf.colors.accent,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        )
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize())
            state.error != null -> ErrorState(
                message = state.error,
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )
            state.programs.isEmpty() -> EmptyState(
                title = "No programs yet",
                description = "Tap the sparkle to design one with the AI coach, or create one on the web.",
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                // Separate programs by status so Active / Drafts / Completed /
                // Archived read as distinct sections rather than one flat list.
                val order = listOf(
                    ProgramStatus.ACTIVE,
                    ProgramStatus.DRAFT,
                    ProgramStatus.COMPLETED,
                    ProgramStatus.ARCHIVED,
                )
                val groups = order.mapNotNull { status ->
                    state.programs.filter { it.status == status }.takeIf { it.isNotEmpty() }
                        ?.let { status to it }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    groups.forEach { (status, programs) ->
                        item(key = "header-${status.name}") {
                            SectionTitle(text = statusGroupLabel(status))
                        }
                        items(programs, key = { it.programId }) { program ->
                            ProgramCard(
                                program = program,
                                onClick = { onOpenProgram(program.programId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Section heading for each program-status group. */
private fun statusGroupLabel(status: ProgramStatus): String = when (status) {
    ProgramStatus.ACTIVE -> "Active"
    ProgramStatus.DRAFT -> "Drafts"
    ProgramStatus.COMPLETED -> "Completed"
    ProgramStatus.ARCHIVED -> "Archived"
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun ProgramsListPreview() {
    HealthFitnessTheme {
        ProgramsListScreen(
            state = ProgramsListUiState(loading = false, programs = ProgramFixtures.listPrograms),
            onBack = {},
            onOpenProgram = {},
            onRetry = {},
        )
    }
}
