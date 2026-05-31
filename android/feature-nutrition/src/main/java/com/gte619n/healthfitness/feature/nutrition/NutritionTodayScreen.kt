package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.MealGroup
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
fun NutritionTodayRoute(
    onOpenTarget: () -> Unit,
    onOpenCapture: () -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: NutritionTodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NutritionTodayScreen(
        state = state,
        onPrevDay = viewModel::previousDay,
        onNextDay = viewModel::nextDay,
        onDeleteEntry = viewModel::deleteEntry,
        onOpenAddSheet = viewModel::openAddSheet,
        onCloseAddSheet = viewModel::closeAddSheet,
        onAddCatalog = viewModel::addCatalogEntry,
        onAddQuick = viewModel::addQuickEntry,
        onOpenTarget = onOpenTarget,
        onOpenCapture = onOpenCapture,
        onBack = onBack,
    )
}

@Composable
fun NutritionTodayScreen(
    state: NutritionTodayUiState,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onDeleteEntry: (String) -> Unit,
    onOpenAddSheet: () -> Unit,
    onCloseAddSheet: () -> Unit,
    onAddCatalog: (Meal, Food, Int, Double) -> Unit,
    onAddQuick: (Meal, String, Macros) -> Unit,
    onOpenTarget: () -> Unit,
    onOpenCapture: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Hf.colors.canvas),
    ) {
        TodayTopBar(
            dateLabel = dayLabel(state.date),
            onPrevDay = onPrevDay,
            onNextDay = onNextDay,
            onOpenTarget = onOpenTarget,
            onOpenCapture = onOpenCapture,
        )
        when {
            state.loading -> CenteredMessage { CircularProgressIndicator(color = Hf.colors.accent) }
            state.error != null && state.day == null -> CenteredMessage {
                Text(state.error, style = Hf.type.bodyMd, color = Hf.colors.alert)
            }
            else -> DayContent(
                day = state.day,
                pendingEntryIds = state.pendingEntryIds,
                onDeleteEntry = onDeleteEntry,
                onOpenAddSheet = onOpenAddSheet,
            )
        }
    }

    if (state.addSheetOpen) {
        AddFoodSheet(
            onDismiss = onCloseAddSheet,
            onAddCatalog = onAddCatalog,
            onAddQuick = onAddQuick,
        )
    }
}

@Composable
private fun TodayTopBar(
    dateLabel: String,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onOpenTarget: () -> Unit,
    onOpenCapture: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.canvas)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconChip(Icons.Outlined.ChevronLeft, "Previous day", onPrevDay)
            Column {
                Text("Nutrition", style = Hf.type.headingLg.copy(fontSize = 20.sp), color = Hf.colors.textPrimary)
                Text(dateLabel, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            }
            IconChip(Icons.Outlined.ChevronRight, "Next day", onNextDay)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconChip(Icons.Outlined.Flag, "Target", onOpenTarget)
            IconChip(Icons.Outlined.CameraAlt, "Capture", onOpenCapture)
        }
    }
}

@Composable
private fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(8.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Hf.colors.textSecondary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun DayContent(
    day: NutritionDay?,
    pendingEntryIds: Set<String>,
    onDeleteEntry: (String) -> Unit,
    onOpenAddSheet: () -> Unit,
) {
    val mealsByName = day?.meals?.associateBy { it.meal } ?: emptyMap()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item("progress") {
            MacroProgressCard(totals = day?.totals, target = day?.target)
        }
        item("add") {
            AddFoodButton(onClick = onOpenAddSheet)
        }
        Meal.entries.forEach { meal ->
            val group = mealsByName[meal.wire]
            item(meal.wire) {
                MealSection(
                    meal = meal,
                    group = group,
                    pendingEntryIds = pendingEntryIds,
                    onDeleteEntry = onDeleteEntry,
                )
            }
        }
        item("tail") { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun AddFoodButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.accent, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = Hf.colors.textInverse, modifier = Modifier.size(16.dp))
        Spacer(Modifier.height(0.dp))
        Text("  ADD FOOD", style = Hf.type.capsSm, color = Hf.colors.textInverse)
    }
}

@Composable
private fun MealSection(
    meal: Meal,
    group: MealGroup?,
    pendingEntryIds: Set<String>,
    onDeleteEntry: (String) -> Unit,
) {
    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(meal.label, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                Text(
                    formatKcal(group?.subtotal?.caloriesKcal ?: 0.0),
                    style = Hf.type.monoSm,
                    color = Hf.colors.textSecondary,
                )
            }
            val entries = group?.entries.orEmpty()
            if (entries.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("No entries yet.", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
            } else {
                entries.forEach { entry ->
                    Spacer(Modifier.height(10.dp))
                    EntryRow(
                        entry = entry,
                        pending = entry.entryId in pendingEntryIds,
                        onDelete = { onDeleteEntry(entry.entryId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: Entry, pending: Boolean, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(entry.foodName, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            Spacer(Modifier.height(2.dp))
            Text(
                "${formatQuantity(entry.quantity)} × ${entry.servingLabel} · ${formatKcal(entry.macros.caloriesKcal)}",
                style = Hf.type.capsSm,
                color = Hf.colors.textTertiary,
            )
        }
        Box(
            modifier = Modifier
                .clickable(enabled = !pending) { onDelete() }
                .padding(6.dp),
        ) {
            if (pending) {
                CircularProgressIndicator(color = Hf.colors.accent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete entry", tint = Hf.colors.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

private fun formatQuantity(q: Double): String =
    if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Preview(showBackground = true, backgroundColor = 0xFFF0EBE0)
@Composable
private fun NutritionTodayPreview() {
    HealthFitnessTheme {
        NutritionTodayScreen(
            state = NutritionTodayUiState(
                loading = false,
                day = NutritionDay(
                    date = "2026-05-30",
                    totals = Macros(1200.0, 90.0, 110.0, 40.0, 18.0, 30.0),
                    target = Macros(2000.0, 150.0, 200.0, 60.0, 30.0, 50.0),
                    meals = listOf(
                        MealGroup(
                            meal = "BREAKFAST",
                            subtotal = Macros(400.0, 30.0),
                            entries = listOf(
                                Entry("e1", "BREAKFAST", "f1", "Oatmeal", "1 cup", 240.0, 1.0, Macros(300.0, 10.0), "CATALOG"),
                            ),
                        ),
                    ),
                ),
            ),
            onPrevDay = {}, onNextDay = {}, onDeleteEntry = {},
            onOpenAddSheet = {}, onCloseAddSheet = {},
            onAddCatalog = { _, _, _, _ -> }, onAddQuick = { _, _, _ -> },
            onOpenTarget = {}, onOpenCapture = {},
        )
    }
}
