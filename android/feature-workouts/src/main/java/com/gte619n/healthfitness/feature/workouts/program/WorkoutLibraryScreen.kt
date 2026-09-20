package com.gte619n.healthfitness.feature.workouts.program

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.workouts.adhoc.AdHocLibraryItem
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.state.EmptyState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Ad-hoc workout Library (IMPL-ADHOC-01) — the Android home for reusable,
 * purpose-driven workouts, a first-class hub section alongside Programs/History
 * (mirrors the web "Library" tab). Read-only for now: it lists the templates the
 * SyncEngine mirrors from the backend (e.g. generated on the web). On-device
 * generating and the guided run (the {@code WorkoutRef} player refactor) are the
 * remaining Phase 4 work. Rendered inside the hub shell, so it has no header.
 */
@Composable
fun WorkoutLibraryRoute(
    modifier: Modifier = Modifier,
    viewModel: WorkoutLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    WorkoutLibraryScreen(items = state.items, modifier = modifier)
}

@Composable
fun WorkoutLibraryScreen(
    items: List<AdHocLibraryItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        EmptyState(
            title = "Workout Library",
            modifier = modifier,
            description =
                "Purpose-driven workouts you can do whenever — a 30-minute hotel-gym " +
                    "session, a bodyweight-plus-one-kettlebell circuit at home. Generate " +
                    "one on the web and it syncs here; on-device generating and the guided " +
                    "run are coming to Android next.",
            icon = Icons.Outlined.AutoAwesome,
        )
        return
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.adhocId }) { item ->
            AdHocLibraryCard(item)
        }
    }
}

@Composable
private fun AdHocLibraryCard(item: AdHocLibraryItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(14.dp))
            .background(Hf.colors.surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = (if (item.pinned) "★ " else "") + item.title,
            style = Hf.type.bodyMd.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
            color = Hf.colors.textPrimary,
        )
        item.summary?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
        }
        val meta = buildList {
            item.estimatedDurationSeconds?.let { add("~${(it + 59) / 60} min") }
            if (item.runCount > 0) add("done ${item.runCount}×")
            item.equipmentLabel?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        if (meta.isNotEmpty()) {
            Text(
                meta.joinToString("  ·  "),
                style = Hf.type.bodySm.copy(fontSize = 11.sp),
                color = Hf.colors.textTertiary,
            )
        }
        if (item.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item.tags.take(3).forEach { tag ->
                    Text(
                        tag,
                        style = Hf.type.bodySm.copy(fontSize = 10.sp),
                        color = Hf.colors.textTertiary,
                        modifier = Modifier
                            .background(Hf.colors.canvas, RoundedCornerShape(999.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun WorkoutLibraryPreview() {
    HealthFitnessTheme {
        WorkoutLibraryScreen(
            items = listOf(
                AdHocLibraryItem("aw_1", "Hotel Full Body", "Bodyweight session for the road",
                    listOf("Travel", "Full-body"), pinned = true, estimatedDurationSeconds = 1800,
                    runCount = 4, equipmentLabel = "Hotel gym"),
                AdHocLibraryItem("aw_2", "Kettlebell 20", null, listOf("Quick"),
                    pinned = false, estimatedDurationSeconds = 1200, runCount = 0,
                    equipmentLabel = "Home"),
            ),
        )
    }
}
