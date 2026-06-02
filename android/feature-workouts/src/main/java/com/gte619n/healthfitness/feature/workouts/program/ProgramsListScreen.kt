package com.gte619n.healthfitness.feature.workouts.program

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.feature.workouts.program.ui.ProgramCard
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf

@Composable
fun ProgramsListRoute(
    onBack: () -> Unit,
    onOpenProgram: (String) -> Unit,
    viewModel: ProgramsListViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProgramsListScreen(
        state = state,
        onBack = onBack,
        onOpenProgram = onOpenProgram,
        onRetry = viewModel::refresh,
    )
}

@Composable
fun ProgramsListScreen(
    state: ProgramsListUiState,
    onBack: () -> Unit,
    onOpenProgram: (String) -> Unit,
    onRetry: () -> Unit,
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
                description = "Programs are created on the web. They'll appear here once you have one.",
                modifier = Modifier.fillMaxSize(),
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(state.programs, key = { it.programId }) { program ->
                    ProgramCard(program = program, onClick = { onOpenProgram(program.programId) })
                }
            }
        }
    }
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
