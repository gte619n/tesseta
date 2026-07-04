package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.MealGroup
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.sync.SyncBadge
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

@Composable
internal fun TodayTopBar(
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
internal fun IconChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
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
internal fun MealSection(
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
internal fun EntryRow(
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
internal fun DragChip(entry: Entry, modifier: Modifier = Modifier) {
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

internal fun formatQuantity(q: Double): String =
    if (q == q.toLong().toDouble()) q.toLong().toString() else q.toString()

@Composable
internal fun CenteredMessage(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
