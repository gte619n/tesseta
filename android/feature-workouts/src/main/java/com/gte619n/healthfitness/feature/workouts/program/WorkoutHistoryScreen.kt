package com.gte619n.healthfitness.feature.workouts.program

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.program.BlockTypeLabels
import com.gte619n.healthfitness.domain.workouts.program.Prescription
import com.gte619n.healthfitness.domain.workouts.program.ScheduledStatus
import com.gte619n.healthfitness.domain.workouts.program.ScheduledWorkout
import com.gte619n.healthfitness.feature.workouts.program.ui.ExerciseThumbnail
import com.gte619n.healthfitness.feature.workouts.program.ui.firstExerciseImageUrl
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

// Prefetch the next page once the user scrolls within this many rows of the end.
private const val LOAD_MORE_THRESHOLD = 5

@Composable
fun WorkoutHistoryRoute(
    onBack: () -> Unit,
    viewModel: WorkoutHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkoutHistoryScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::load,
        onLoadMore = viewModel::loadMore,
        onDelete = viewModel::deleteSession,
    )
}

@Composable
fun WorkoutHistoryScreen(
    state: WorkoutHistoryViewModel.State,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onDelete: (ScheduledWorkout) -> Unit = {},
) {
    // The detail is rendered in-screen from the already-loaded session (no
    // re-fetch, and — unlike opening the logger — no draft is created).
    var selected by remember { mutableStateOf<ScheduledWorkout?>(null) }
    // Hoisted so the list keeps its scroll position when the detail closes
    // (e.g. after a delete) instead of snapping back to the top.
    val listState = rememberLazyListState()

    val current = selected
    if (current != null) {
        BackHandler { selected = null }
        HistoryDetail(
            session = current,
            onBack = { selected = null },
            onDelete = {
                onDelete(current)
                selected = null
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = "Workout history",
            subtitle = "Every workout you've finished",
            onBack = onBack,
        )
        when {
            state.loading -> LoadingState(Modifier.fillMaxSize())
            state.error != null -> ErrorState(
                message = state.error,
                modifier = Modifier.fillMaxSize(),
                onRetry = onRetry,
            )
            state.sessions.isEmpty() -> EmptyState(
                title = "No finished workouts yet",
                description = "Workouts you complete will show up here to review.",
                modifier = Modifier.fillMaxSize(),
            )
            else -> {
                // Load the next page once the list is scrolled near its end.
                val loadMoreWhenNearEnd by remember {
                    derivedStateOf {
                        val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        last >= state.sessions.size - LOAD_MORE_THRESHOLD
                    }
                }
                LaunchedEffect(loadMoreWhenNearEnd, state.hasMore, state.loadingMore) {
                    if (loadMoreWhenNearEnd && state.hasMore && !state.loadingMore) onLoadMore()
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        state.sessions,
                        key = { _, s -> s.scheduledId },
                    ) { index, session ->
                        val prev = state.sessions.getOrNull(index - 1)
                        // A month divider opens each new calendar month in the
                        // time-ordered list ("June 2026").
                        if (prev == null || !sameMonth(prev.date, session.date)) {
                            MonthDivider(label = monthYearLabel(session.date), firstOfList = index == 0)
                        }
                        // A header opens each new program/phase run. phaseId is
                        // globally unique, so a change marks a new group.
                        if (prev == null || prev.phaseId != session.phaseId) {
                            GroupHeader(session = session)
                        }
                        HistoryRow(session = session, onClick = { selected = session })
                    }
                    if (state.loadingMore) {
                        item(key = "loading-more") {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Hf.colors.textTertiary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A bold month divider ("June 2026") opening each new calendar month. */
@Composable
private fun MonthDivider(label: String, firstOfList: Boolean) {
    Text(
        label,
        style = Hf.type.headingMd,
        color = Hf.colors.textPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (firstOfList) 0.dp else 18.dp, bottom = 4.dp),
    )
}

/** Delineation header opening a program/phase run in the time-ordered list. */
@Composable
private fun GroupHeader(session: ScheduledWorkout) {
    val program = session.programTitle?.trim().orEmpty().ifBlank { "Workout" }
    val phase = session.phaseTitle?.trim().orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        CapsLabel(program, color = Hf.colors.textSecondary)
        if (phase.isNotBlank()) {
            Spacer(Modifier.size(6.dp))
            CapsLabel("· $phase", color = Hf.colors.textTertiary)
        }
    }
}

@Composable
private fun HistoryRow(session: ScheduledWorkout, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        ExerciseThumbnail(firstExerciseImageUrl(session), session.dayLabel)
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                session.dayLabel.ifBlank { "Workout" },
                style = Hf.type.headingMd.copy(fontSize = 14.sp),
                color = Hf.colors.textPrimary,
            )
            Spacer(Modifier.height(2.dp))
            CapsLabel(scheduledDateLabel(session.date), color = Hf.colors.textTertiary)
            Spacer(Modifier.height(4.dp))
            Text(
                historySummaryLine(session),
                style = Hf.type.monoSm,
                color = Hf.colors.textSecondary,
            )
        }
        Text("›", style = Hf.type.headingLg, color = Hf.colors.textTertiary)
    }
}

@Composable
private fun HistoryDetail(
    session: ScheduledWorkout,
    onBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        HfScreenHeader(
            title = session.dayLabel.ifBlank { "Workout" },
            subtitle = "${scheduledDateLabel(session.date)} · ${historySummaryLine(session)}",
            onBack = onBack,
            trailing = {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete workout",
                    tint = Hf.colors.alert,
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { confirmDelete = true }
                        .padding(9.dp),
                )
            },
        )
        if (confirmDelete) {
            ConfirmDialog(
                title = "Delete this workout?",
                message = "The logged result for ${session.dayLabel.ifBlank { "this workout" }} " +
                    "(${scheduledDateLabel(session.date)}) will be removed from your history. " +
                    "You can run it again later.",
                confirmLabel = "Delete",
                dismissLabel = "Cancel",
                destructive = true,
                onConfirm = {
                    confirmDelete = false
                    onDelete()
                },
                onDismiss = { confirmDelete = false },
            )
        }
        val day = session.session
        if (day == null || day.blocks.isEmpty()) {
            EmptyState(
                title = "Nothing logged",
                description = "This workout has no recorded sets.",
                modifier = Modifier.fillMaxSize(),
            )
            return
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 8.dp),
        ) {
            day.blocks.sortedBy { it.orderIndex }.forEach { block ->
                Spacer(Modifier.height(8.dp))
                SectionTitle(text = BlockTypeLabels.label(block.type), compact = true)
                Spacer(Modifier.height(8.dp))
                block.prescriptions.sortedBy { it.orderIndex }.forEach { prescription ->
                    HistoryExerciseCard(prescription)
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** One performed exercise: name, the prescribed target, and the sets actually logged. */
@Composable
private fun HistoryExerciseCard(prescription: Prescription) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            prescription.exercise?.name ?: prescription.exerciseId,
            style = Hf.type.headingMd.copy(fontSize = 14.sp),
            color = Hf.colors.textPrimary,
        )
        val target = prescriptionSummary(prescription)
        if (target.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(target, style = Hf.type.monoSm, color = Hf.colors.textTertiary)
        }
        val logged = loggedSetsSummary(prescription)
        Spacer(Modifier.height(6.dp))
        if (logged != null) {
            Text(logged, style = Hf.type.monoMd, color = Hf.colors.textPrimary)
        } else {
            Text("Not logged", style = Hf.type.bodySm, color = Hf.colors.textQuaternary)
        }
    }
}

/** "5 sets · 47 min" performed-work summary for a completed session. */
private fun historySummaryLine(session: ScheduledWorkout): String {
    val sets = session.session?.blocks
        ?.sumOf { block -> block.prescriptions.sumOf { it.loggedSets.size } }
        ?: 0
    val setsLabel = if (sets == 1) "1 set" else "$sets sets"
    val seconds = session.durationSeconds
    return if (seconds != null && seconds > 0) {
        "$setsLabel · ${durationText(seconds)}"
    } else {
        setsLabel
    }
}

private fun durationText(seconds: Int): String = when {
    seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    seconds >= 60 -> "${seconds / 60} min"
    else -> "${seconds}s"
}

/** "June 2026" — the month divider label. */
private fun monthYearLabel(date: java.time.LocalDate): String =
    "${date.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} ${date.year}"

private fun sameMonth(a: java.time.LocalDate, b: java.time.LocalDate): Boolean =
    a.year == b.year && a.month == b.month

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0, heightDp = 900)
@Composable
private fun WorkoutHistoryPreview() {
    val completed = ProgramFixtures.scheduledWithSession.copy(
        status = ScheduledStatus.COMPLETED,
        durationSeconds = 2_840,
        programTitle = "Hypertrophy Block",
        phaseTitle = "Base",
    )
    HealthFitnessTheme {
        WorkoutHistoryScreen(
            state = WorkoutHistoryViewModel.State(loading = false, sessions = listOf(completed)),
            onBack = {},
        )
    }
}
