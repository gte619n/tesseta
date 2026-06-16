package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.MealGroup
import com.gte619n.healthfitness.domain.nutrition.NutritionDay
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.sync.SyncBadge
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalDate

@Composable
fun NutritionTodayRoute(
    onOpenTarget: () -> Unit,
    onOpenCapture: (LocalDate) -> Unit,
    onBack: (() -> Unit)? = null,
    viewModel: NutritionTodayViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Refresh whenever the page returns to the foreground — e.g. after a barcode
    // scan logs an entry and pops back here — so the new food shows up.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    NutritionTodayScreen(
        state = state,
        onRefresh = viewModel::onPullRefresh,
        onPrevDay = viewModel::previousDay,
        onNextDay = viewModel::nextDay,
        onDeleteEntry = viewModel::deleteEntry,
        onRetryImage = viewModel::regenerateEntryImage,
        onMoveEntry = viewModel::moveEntry,
        onOpenEditSheet = viewModel::openEditSheet,
        onCloseEditSheet = viewModel::closeEditSheet,
        onUpdateEntry = viewModel::updateEntry,
        onSaveComposite = viewModel::saveCompositeMeal,
        onOpenAddSheet = viewModel::openAddSheet,
        onCloseAddSheet = viewModel::closeAddSheet,
        onAddCatalog = viewModel::addCatalogEntry,
        onAddQuick = viewModel::addQuickEntry,
        onDescribeAsync = viewModel::describeMealAsync,
        onRelogRecent = viewModel::relogRecent,
        onOpenTarget = onOpenTarget,
        onOpenCapture = onOpenCapture,
        onBack = onBack,
    )
}

@Composable
fun NutritionTodayScreen(
    state: NutritionTodayUiState,
    onRefresh: () -> Unit,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
    onDeleteEntry: (String) -> Unit,
    onRetryImage: (String) -> Unit,
    onMoveEntry: (String, String) -> Unit,
    onOpenEditSheet: (Entry) -> Unit,
    onCloseEditSheet: () -> Unit,
    onUpdateEntry: (String, com.gte619n.healthfitness.domain.nutrition.EntryPatchRequest) -> Unit,
    onSaveComposite: (String, String, Double, List<Double>) -> Unit,
    onOpenAddSheet: () -> Unit,
    onCloseAddSheet: () -> Unit,
    onAddCatalog: (Meal, Food, Int, Double) -> Unit,
    onAddQuick: (Meal, String, Macros) -> Unit,
    onDescribeAsync: (Meal, String) -> Unit,
    onRelogRecent: (Meal, Entry) -> Unit,
    onOpenTarget: () -> Unit,
    onOpenCapture: (LocalDate) -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        TodayTopBar(
            dateLabel = dayLabel(state.date),
            onPrevDay = onPrevDay,
            onNextDay = onNextDay,
            onOpenTarget = onOpenTarget,
            onOpenCapture = { onOpenCapture(state.date) },
            onOpenAddSheet = onOpenAddSheet,
            onBack = onBack,
        )
        when {
            state.loading -> CenteredMessage { CircularProgressIndicator(color = Hf.colors.accent) }
            state.error != null && state.day == null -> CenteredMessage {
                Text(state.error, style = Hf.type.bodyMd, color = Hf.colors.alert)
            }
            else -> DayContent(
                day = state.day.withPendingCaptures(state.pendingCaptures, state.date),
                pendingEntryIds = state.pendingEntryIds,
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                onDeleteEntry = onDeleteEntry,
                onRetryImage = onRetryImage,
                onMoveEntry = onMoveEntry,
                onOpenEditSheet = onOpenEditSheet,
            )
        }
    }

    if (state.addSheetOpen) {
        AddFoodSheet(
            onDismiss = onCloseAddSheet,
            onAddCatalog = onAddCatalog,
            onAddQuick = onAddQuick,
            onDescribeAsync = onDescribeAsync,
            onRelogRecent = onRelogRecent,
        )
    }

    val editing = state.editingEntry
    if (editing != null) {
        EditEntrySheet(
            entry = editing,
            saving = state.savingEdit,
            onDismiss = onCloseEditSheet,
            onSave = onUpdateEntry,
        )
    }

    val composite = state.editingComposite
    if (composite != null) {
        IngredientsSheet(
            entry = composite,
            saving = state.savingIngredient,
            onDismiss = onCloseEditSheet,
            onSave = { title, portion, quantities ->
                onSaveComposite(composite.entryId, title, portion, quantities)
            },
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
    onOpenAddSheet: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    // Canonical header row (shared component): back arrow + title/subtitle.
    HfScreenHeader(
        title = "Nutrition",
        subtitle = dateLabel,
        onBack = onBack,
    )

    // Secondary row beneath the canonical header: day navigation plus the
    // Target / Capture / Add actions (relocated out of the title row).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .padding(bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconChip(Icons.Outlined.ChevronLeft, "Previous day", onPrevDay)
            IconChip(Icons.Outlined.ChevronRight, "Next day", onNextDay)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            IconChip(Icons.Outlined.Flag, "Target", onOpenTarget)
            IconChip(Icons.Outlined.CameraAlt, "Capture", onOpenCapture)
            IconChip(Icons.Outlined.Add, "Add food", onOpenAddSheet)
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

/** An entry being dragged, plus the current pointer position in window space. */
private data class MealDragState(val entry: Entry, val pointerWindow: Offset)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DayContent(
    day: NutritionDay?,
    pendingEntryIds: Set<String>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onDeleteEntry: (String) -> Unit,
    onRetryImage: (String) -> Unit,
    onMoveEntry: (String, String) -> Unit,
    onOpenEditSheet: (Entry) -> Unit,
) {
    val mealsByName = day?.meals?.associateBy { it.meal } ?: emptyMap()
    val density = LocalDensity.current

    // Window-space vertical bounds of each meal section, captured as they lay
    // out, so a dragged entry can resolve which meal it's currently over.
    val mealBounds = remember { mutableStateMapOf<String, ClosedFloatingPointRange<Float>>() }
    var drag by remember { mutableStateOf<MealDragState?>(null) }
    var boxTopInWindow by remember { mutableStateOf(0f) }

    fun targetMealAt(windowY: Float): String? =
        mealBounds.entries.firstOrNull { windowY in it.value }?.key

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { boxTopInWindow = it.positionInWindow().y },
    ) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            item("progress") {
                MacroProgressCard(totals = day?.totals, target = day?.target)
            }
            Meal.entries.forEach { meal ->
                val group = mealsByName[meal.wire]
                item(meal.wire) {
                    val active = drag
                    MealSection(
                        meal = meal,
                        group = group,
                        pendingEntryIds = pendingEntryIds,
                        draggingEntryId = active?.entry?.entryId,
                        isDropTarget = active != null &&
                            active.entry.meal != meal.wire &&
                            targetMealAt(active.pointerWindow.y) == meal.wire,
                        onBounds = { top, bottom -> mealBounds[meal.wire] = top..bottom },
                        onDeleteEntry = onDeleteEntry,
                        onRetryImage = onRetryImage,
                        onOpenEditSheet = onOpenEditSheet,
                        onDragStart = { entry, pointer -> drag = MealDragState(entry, pointer) },
                        onDrag = { pointer -> drag = drag?.copy(pointerWindow = pointer) },
                        onDragEnd = {
                            val d = drag
                            drag = null
                            if (d != null) {
                                val target = targetMealAt(d.pointerWindow.y)
                                if (target != null && target != d.entry.meal) {
                                    onMoveEntry(d.entry.entryId, target)
                                }
                            }
                        },
                        onDragCancel = { drag = null },
                    )
                }
            }
            item("tail") { Spacer(Modifier.height(8.dp)) }
        }
        }

        // A chip that follows the finger while dragging, so the in-flight move
        // is legible even as the source row dims in place.
        drag?.let { d ->
            val xDp = with(density) { d.pointerWindow.x.toDp() }
            val yDp = with(density) { (d.pointerWindow.y - boxTopInWindow).toDp() }
            DragChip(
                entry = d.entry,
                modifier = Modifier.offset(x = xDp - 70.dp, y = yDp - 24.dp),
            )
        }
    }
}

@Composable
private fun MealSection(
    meal: Meal,
    group: MealGroup?,
    pendingEntryIds: Set<String>,
    draggingEntryId: String?,
    isDropTarget: Boolean,
    onBounds: (Float, Float) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onRetryImage: (String) -> Unit,
    onOpenEditSheet: (Entry) -> Unit,
    onDragStart: (Entry, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .onGloballyPositioned {
            val top = it.positionInWindow().y
            onBounds(top, top + it.size.height)
        }
        .then(
            if (isDropTarget) {
                Modifier.border(1.5.dp, Hf.colors.accent, RoundedCornerShape(10.dp))
            } else {
                Modifier
            },
        )
    HfCard(modifier = cardModifier) {
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
                Text(
                    if (isDropTarget) "Drop here" else "No entries yet.",
                    style = Hf.type.bodySm,
                    color = if (isDropTarget) Hf.colors.accent else Hf.colors.textTertiary,
                )
            } else {
                entries.forEach { entry ->
                    Spacer(Modifier.height(10.dp))
                    EntryRow(
                        entry = entry,
                        pending = entry.entryId in pendingEntryIds,
                        dragging = entry.entryId == draggingEntryId,
                        onClick = { onOpenEditSheet(entry) },
                        onDelete = { onDeleteEntry(entry.entryId) },
                        onRetryImage = { onRetryImage(entry.entryId) },
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onDragCancel = onDragCancel,
                    )
                }
            }
        }
    }
}

@Composable
private fun EntryRow(
    entry: Entry,
    pending: Boolean,
    dragging: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRetryImage: () -> Unit,
    onDragStart: (Entry, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    // Track this row's window origin so drag offsets (which arrive relative to
    // the row) can be reported in window space for hit-testing meal sections.
    var rowOriginInWindow by remember { mutableStateOf(Offset.Zero) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { rowOriginInWindow = it.positionInWindow() }
            .graphicsLayer { alpha = if (dragging) 0.3f else 1f }
            // Long-press to pick the entry up, then drag onto another meal.
            // Drags are consumed so the list doesn't scroll mid-move.
            .pointerInput(entry.entryId, pending) {
                if (pending) return@pointerInput
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset -> onDragStart(entry, rowOriginInWindow + offset) },
                    onDrag = { change, _ ->
                        change.consume()
                        onDrag(rowOriginInWindow + change.position)
                    },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() },
                )
            }
            .clickable(enabled = !pending) { onClick() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // An entry still being analyzed shows the generating spinner; its real
        // name/macros/image arrive via polling once the backend finishes.
        FoodThumbnail(
            imageUrl = entry.imageUrl,
            imageStatus = if (entry.isAnalyzing) "PENDING" else entry.imageStatus,
            size = 40.dp,
            onRetry = onRetryImage,
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.foodName, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
                // #40: per-row PENDING/FAILED badge for an offline nutrition write.
                SyncBadge(syncState = entry.syncState)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                when {
                    entry.isAnalyzing -> "Analyzing your photo…"
                    entry.analysisStatus == "FAILED" -> "Couldn’t read photo · delete and retry"
                    else -> {
                        val qtyPrefix = if (entry.quantity != 1.0) "${formatQuantity(entry.quantity)} × " else ""
                        "$qtyPrefix${entry.servingLabel.orEmpty()} · ${formatKcal(entry.macros.caloriesKcal)}"
                    }
                },
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

/** Floating preview of the entry being dragged between meals. */
@Composable
private fun DragChip(entry: Entry, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .shadow(8.dp, RoundedCornerShape(10.dp))
            .background(Hf.colors.surface, RoundedCornerShape(10.dp))
            .border(0.5.dp, Hf.colors.accent, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FoodThumbnail(imageUrl = entry.imageUrl, imageStatus = entry.imageStatus, size = 28.dp, zoomable = false)
        Spacer(Modifier.width(8.dp))
        Text(entry.foodName, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
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
            onRefresh = {},
            onPrevDay = {}, onNextDay = {}, onDeleteEntry = {}, onRetryImage = {}, onMoveEntry = { _, _ -> },
            onOpenEditSheet = {}, onCloseEditSheet = {}, onUpdateEntry = { _, _ -> },
            onSaveComposite = { _, _, _, _ -> },
            onOpenAddSheet = {}, onCloseAddSheet = {},
            onAddCatalog = { _, _, _, _ -> }, onAddQuick = { _, _, _ -> },
            onDescribeAsync = { _, _ -> }, onRelogRecent = { _, _ -> },
            onOpenTarget = {}, onOpenCapture = {},
        )
    }
}
