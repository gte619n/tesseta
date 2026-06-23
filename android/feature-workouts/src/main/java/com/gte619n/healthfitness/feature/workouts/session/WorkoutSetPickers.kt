package com.gte619n.healthfitness.feature.workouts.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.roundToInt

/** Weight wheel step (lb) and ceiling. */
private const val WEIGHT_STEP = 5
private const val WEIGHT_MAX = 700

/**
 * Reps picker popup: a large readout with oversized −/+ steppers, so reps are
 * dialed in by thumb without the on-screen keyboard (IMPL-COACH UX).
 */
@Composable
fun RepsPickerDialog(
    exerciseName: String,
    initial: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var reps by remember { mutableIntStateOf(initial.coerceIn(0, 99)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                CapsLabel("Reps", color = Hf.colors.textTertiary)
                Text(exerciseName, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
            }
        },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepButton(Icons.Outlined.Remove, "Fewer reps") { reps = (reps - 1).coerceAtLeast(0) }
                Text(
                    reps.toString(),
                    style = Hf.type.headingLg.copy(fontSize = 56.sp),
                    color = Hf.colors.textPrimary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(96.dp),
                )
                StepButton(Icons.Outlined.Add, "More reps") { reps = (reps + 1).coerceAtMost(99) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reps) }) {
                Text("Set", style = Hf.type.bodyMd, color = Hf.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = Hf.type.bodyMd, color = Hf.colors.textTertiary)
            }
        },
        containerColor = Hf.colors.surface,
    )
}

@Composable
private fun StepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .background(Hf.colors.accentBg, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Hf.colors.accent, modifier = Modifier.size(30.dp))
    }
}

/**
 * Weight picker popup: a vertical wheel in 5-lb increments that the user scrolls
 * (no keyboard). The value under the fixed center band is the selection; `pad`
 * blank rows on each end let the first/last real value reach the center.
 */
@Composable
fun WeightPickerDialog(
    exerciseName: String,
    initialLbs: Double?,
    onConfirm: (Double?) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember { (0..(WEIGHT_MAX / WEIGHT_STEP)).map { it * WEIGHT_STEP } }
    val itemHeight = 56.dp
    val visibleRows = 5
    val pad = visibleRows / 2 // blank rows on each end so item 0 / last can center

    val initialIndex = remember {
        ((initialLbs ?: 0.0) / WEIGHT_STEP).roundToInt().coerceIn(0, values.lastIndex)
    }
    // With `pad` leading blanks, the value centered under the band is exactly
    // values[firstVisibleItemIndex]; snapping aligns it after each scroll.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val selectedIndex by remember {
        derivedStateOf { listState.firstVisibleItemIndex.coerceIn(0, values.lastIndex) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(color = Hf.colors.surface, shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                CapsLabel("Weight · lb", color = Hf.colors.textTertiary)
                Text(exerciseName, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().height(itemHeight * visibleRows),
                    contentAlignment = Alignment.Center,
                ) {
                    // The fixed center selection band.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(itemHeight)
                            .background(Hf.colors.accentBg, RoundedCornerShape(10.dp)),
                    )
                    LazyColumn(
                        state = listState,
                        flingBehavior = rememberSnapFlingBehavior(listState),
                    ) {
                        blankRows(pad, itemHeight)
                        itemsIndexed(values) { i, v ->
                            val selected = i == selectedIndex
                            Box(
                                Modifier.fillMaxWidth().height(itemHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    v.toString(),
                                    style = Hf.type.headingLg.copy(
                                        fontSize = if (selected) 34.sp else 24.sp,
                                    ),
                                    color = if (selected) Hf.colors.textPrimary else Hf.colors.textTertiary,
                                )
                            }
                        }
                        blankRows(pad, itemHeight)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", style = Hf.type.bodyMd, color = Hf.colors.textTertiary)
                    }
                    TextButton(onClick = { onConfirm(values[selectedIndex].toDouble()) }) {
                        Text("Set", style = Hf.type.bodyMd, color = Hf.colors.accent)
                    }
                }
            }
        }
    }
}

/** Blank spacer rows padding a wheel so its first/last real value can center. */
private fun LazyListScope.blankRows(count: Int, height: Dp) {
    items(count) { Spacer(Modifier.height(height)) }
}
