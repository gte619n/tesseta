package com.gte619n.healthfitness.feature.workouts.program

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.program.BlockTypeLabels
import com.gte619n.healthfitness.domain.workouts.program.ExerciseSummary
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.WorkoutDay
import com.gte619n.healthfitness.feature.workouts.program.ui.ExerciseDetailSheet
import com.gte619n.healthfitness.feature.workouts.program.ui.ExerciseTile
import com.gte619n.healthfitness.feature.workouts.program.ui.PrescriptionRow
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/** Tiles per row in the tablet grid. */
private const val TILE_COLUMNS = 3

@Composable
fun WorkoutDetailRoute(
    onBack: () -> Unit,
    viewModel: WorkoutDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkoutDetailScreen(state = state, onBack = onBack, onRetry = viewModel::refresh)
}

@Composable
fun WorkoutDetailScreen(
    state: WorkoutDetailUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
) {
    var selectedExercise by remember { mutableStateOf<ExerciseSummary?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        val day = state.day
        HfScreenHeader(
            title = day?.label?.ifBlank { dayOfWeekLabel(day.dayOfWeek) } ?: "Workout",
            subtitle = state.phaseTitle ?: state.programTitle,
            onBack = onBack,
        )
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize())
            state.error != null && day == null -> ErrorState(
                message = state.error,
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )
            day != null -> WorkoutDetailBody(
                day = day,
                tiled = currentWidthSizeClass() != WindowWidthSizeClass.Compact,
                onOpenExercise = { selectedExercise = it },
            )
        }
    }

    selectedExercise?.let { exercise ->
        ExerciseDetailSheet(summary = exercise, onDismiss = { selectedExercise = null })
    }
}

@Composable
private fun WorkoutDetailBody(
    day: WorkoutDay,
    tiled: Boolean,
    onOpenExercise: (ExerciseSummary) -> Unit,
) {
    val blocks = day.blocks.sortedBy { it.orderIndex }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            val meta = buildList {
                add(dayOfWeekLabel(day.dayOfWeek))
                day.locationName?.takeIf { it.isNotBlank() }?.let { add(it) }
                add(exerciseCountLabel(day))
            }
            CapsLabel(meta.joinToString(" · "), color = Hf.colors.textTertiary)
        }

        if (blocks.all { it.prescriptions.isEmpty() }) {
            item {
                Text(
                    "This workout has no exercises yet.",
                    style = Hf.type.bodyMd,
                    color = Hf.colors.textSecondary,
                )
            }
        }

        blocks.forEach { block ->
            val prescriptions = block.prescriptions.sortedBy { it.orderIndex }
            if (prescriptions.isEmpty()) return@forEach

            item(key = "title-${block.blockId}") {
                SectionTitle(text = BlockTypeLabels.label(block.type))
            }

            if (tiled) {
                items(
                    items = prescriptions.chunked(TILE_COLUMNS),
                    key = { row -> "tiles-${block.blockId}-${row.first().orderIndex}" },
                ) { rowItems ->
                    TileRow(prescriptions = rowItems, onOpenExercise = onOpenExercise)
                }
            } else {
                items(
                    items = prescriptions,
                    key = { "row-${block.blockId}-${it.orderIndex}" },
                ) { prescription ->
                    PrescriptionRow(prescription = prescription, onOpenExercise = onOpenExercise)
                }
            }
        }
    }
}

/** One row of up to [TILE_COLUMNS] exercise tiles, padded so widths stay even. */
@Composable
private fun TileRow(
    prescriptions: List<Prescription>,
    onOpenExercise: (ExerciseSummary) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        prescriptions.forEach { prescription ->
            ExerciseTile(
                prescription = prescription,
                onOpenExercise = onOpenExercise,
                modifier = Modifier.weight(1f),
            )
        }
        // Keep the last (short) row aligned to the column grid.
        repeat(TILE_COLUMNS - prescriptions.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * Derive a [WindowWidthSizeClass] from the current screen width without needing
 * an Activity reference (mirrors the medications list pattern).
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

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0, heightDp = 900)
@Composable
private fun WorkoutDetailPreview() {
    HealthFitnessTheme {
        WorkoutDetailScreen(
            state = WorkoutDetailUiState(
                loading = false,
                programTitle = "12-Week Strength Base",
                phaseTitle = "Accumulation",
                day = ProgramFixtures.deepProgram.phases.first().days.first(),
            ),
            onBack = {},
            onRetry = {},
        )
    }
}
