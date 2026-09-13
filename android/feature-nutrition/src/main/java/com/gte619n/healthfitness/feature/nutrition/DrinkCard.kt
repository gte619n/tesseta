package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.DrinkSession
import com.gte619n.healthfitness.domain.nutrition.DrinkSessionSummary
import com.gte619n.healthfitness.domain.nutrition.DrinkTally
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * IMPL-DRINK-01 (Phase 3) — the home Drink card, pinned to the TOP of the phone +
 * foldable dashboards and gated on Drink Mode (D7). Renders nothing when Drink Mode
 * is off. When on: a "Start a session" empty state, or an active session with the
 * live headline (std drinks + kcal), secondary line, logged list, End button and
 * the drink tiles (recents first, then a searchable full list).
 *
 * One-tap logs instantly with an "Added — Undo" snackbar (~4s); long-press opens a
 * ×0.5/×1/×1.5/×2 picker (D22). All logging is offline-first.
 */
@Composable
fun DrinkCard(
    modifier: Modifier = Modifier,
    viewModel: DrinkSessionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Warm the offline drink cache + budget/recents on every foreground (§4.3).
    // Runs even while Drink Mode is off so it's ready the instant it's enabled.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // The one-time End summary is hoisted above the Drink Mode gate: ending a session
    // also exits Drink Mode (endSession), which early-returns the card — so the summary
    // must live here to stay visible until the user dismisses it.
    state.summary?.let { summary ->
        DrinkSummaryDialog(summary = summary, onDismiss = viewModel::dismissSummary)
    }

    if (!state.drinkModeEnabled) return

    // Self-hosted snackbar so the card drops into either dashboard as a one-liner
    // without the host needing a Scaffold/SnackbarHost. Anchored under the card.
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier) {
        DrinkCardContent(
            state = state,
            modifier = Modifier.fillMaxWidth(),
            onStart = viewModel::startSession,
            onQueryChange = viewModel::setQuery,
            onLog = { food, qty ->
                val entryId = viewModel.logDrink(food, qty)
                if (entryId != null) {
                    scope.launch {
                        // ~4s "Added — Undo" (D13); Undo pops the tally + entry.
                        snackbarHost.currentSnackbarData?.dismiss()
                        val result = snackbarHost.showSnackbar(
                            message = "Added ${food.name}",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) viewModel.undo(entryId)
                    }
                }
            },
            onEnd = viewModel::endSession,
        )
        SnackbarHost(hostState = snackbarHost)
        // Separates the pinned card from the vitals below (only present when the
        // card renders — DrinkCard early-returns when Drink Mode is off).
        Spacer(Modifier.height(11.dp))
    }
}

@Composable
private fun DrinkCardContent(
    state: DrinkCardUiState,
    modifier: Modifier = Modifier,
    onStart: () -> Unit,
    onQueryChange: (String) -> Unit,
    onLog: (Food, Double) -> Unit,
    onEnd: (Long) -> Unit,
) {
    HfCard(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.LocalBar,
                    contentDescription = null,
                    tint = Hf.colors.accent,
                    modifier = Modifier.size(18.dp),
                )
                SectionTitle("Drinks")
            }

            if (!state.session.active) {
                DrinkEmptyState(onStart = onStart)
            } else {
                ActiveSession(
                    state = state,
                    onQueryChange = onQueryChange,
                    onLog = onLog,
                    onEnd = onEnd,
                )
            }
        }
    }
}

@Composable
private fun DrinkEmptyState(onStart: () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Text(
        "Track a night out — log drinks with one tap.",
        style = Hf.type.bodySm,
        color = Hf.colors.textTertiary,
    )
    Spacer(Modifier.height(10.dp))
    PrimaryPill(label = "Start a session", onClick = onStart)
}

@Composable
private fun ActiveSession(
    state: DrinkCardUiState,
    onQueryChange: (String) -> Unit,
    onLog: (Food, Double) -> Unit,
    onEnd: (Long) -> Unit,
) {
    val tally = state.tally
    var showEndDialog by remember { mutableStateOf(false) }

    Spacer(Modifier.height(6.dp))
    // D21 reminder: always show the session start day + time.
    Text(
        "Started ${startedLabel(state.session.startedAtMillis)}",
        style = Hf.type.capsSm,
        color = Hf.colors.textTertiary,
    )

    Spacer(Modifier.height(8.dp))
    // Headline: standard drinks + total calories (D14).
    Text(
        "${formatStd(tally.standardDrinks)} std · ${formatKcal(tally.kcal)}",
        style = Hf.type.headingLg.copy(fontSize = 22.sp),
        color = Hf.colors.textPrimary,
    )
    Spacer(Modifier.height(2.dp))
    // Secondary: sugar/carbs + (if a target exists) budget dent.
    Text(
        secondaryLine(tally, state.calorieTarget, state.kcalLoggedToday),
        style = Hf.type.bodySm,
        color = Hf.colors.textTertiary,
    )

    if (state.session.loggedDrinks.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.session.loggedDrinks.takeLast(6).reversed().forEach { LoggedRow(it) }
        }
    }

    Spacer(Modifier.height(12.dp))
    DrinkTiles(
        recents = state.recentDrinks,
        results = state.searchResults,
        query = state.query,
        onQueryChange = onQueryChange,
        onLog = onLog,
    )

    Spacer(Modifier.height(12.dp))
    SecondaryPill(label = "End session", onClick = { showEndDialog = true })

    if (showEndDialog) {
        EndSessionDialog(
            startedAtMillis = state.session.startedAtMillis,
            lastDrinkAtMillis = state.session.loggedDrinks.lastOrNull()?.atMillis,
            onConfirm = { close ->
                showEndDialog = false
                onEnd(close)
            },
            onDismiss = { showEndDialog = false },
        )
    }
}

@Composable
private fun LoggedRow(drink: DrinkSession.LoggedDrink) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val qtyPrefix = if (drink.quantity != 1.0) "${formatQuantity(drink.quantity)}× " else ""
        Text(
            "$qtyPrefix${drink.name}",
            style = Hf.type.bodySm,
            color = Hf.colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${formatStd(drink.standardDrinks)} std",
            style = Hf.type.capsSm,
            color = Hf.colors.textTertiary,
        )
    }
}

@Composable
private fun DrinkTiles(
    recents: List<Food>,
    results: List<Food>,
    query: String,
    onQueryChange: (String) -> Unit,
    onLog: (Food, Double) -> Unit,
) {
    if (recents.isNotEmpty() && query.isBlank()) {
        Text("Recent", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(recents) { food -> DrinkTile(food = food, onLog = onLog) }
        }
        Spacer(Modifier.height(10.dp))
    }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search my drinks", style = Hf.type.bodySm) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))

    if (results.isEmpty()) {
        Text(
            if (query.isBlank()) "No drinks yet — add some on the web." else "No matches.",
            style = Hf.type.bodySm,
            color = Hf.colors.textTertiary,
        )
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(results) { food -> DrinkTile(food = food, onLog = onLog) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrinkTile(food: Food, onLog: (Food, Double) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(88.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Hf.colors.surface)
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = { onLog(food, 1.0) },
                onLongClick = { showPicker = true },
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        DrinkImage(food = food, size = 56.dp)
        Text(
            food.name,
            style = Hf.type.capsSm.copy(fontSize = 10.sp),
            color = Hf.colors.textPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${formatStd(food.alcohol?.standardDrinks ?: 0.0)} std",
            style = Hf.type.capsSm.copy(fontSize = 9.sp),
            color = Hf.colors.textTertiary,
        )
    }

    if (showPicker) {
        QuantityPickerDialog(
            drinkName = food.name,
            onPick = { q ->
                showPicker = false
                onLog(food, q)
            },
            onDismiss = { showPicker = false },
        )
    }
}

/** Drink glassware image, or a glass placeholder when it isn't READY (D12). */
@Composable
private fun DrinkImage(food: Food, size: androidx.compose.ui.unit.Dp) {
    if (food.imageStatus == "READY" && food.imageUrl != null) {
        FoodThumbnail(
            imageUrl = food.imageUrl,
            imageStatus = food.imageStatus,
            size = size,
            zoomable = false,
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(Hf.colors.canvasSunken),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.LocalBar,
                contentDescription = null,
                tint = Hf.colors.textTertiary,
                modifier = Modifier.size(size * 0.45f),
            )
        }
    }
}

@Composable
private fun QuantityPickerDialog(
    drinkName: String,
    onPick: (Double) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(drinkName, style = Hf.type.headingSm) },
        text = { Text("Pour size", style = Hf.type.bodySm, color = Hf.colors.textTertiary) },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DRINK_QUANTITIES.forEach { q ->
                    SecondaryPill(label = "×${formatQuantity(q)}", onClick = { onPick(q) })
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EndSessionDialog(
    startedAtMillis: Long,
    lastDrinkAtMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // D21: a fully editable close date + time. It defaults to one hour after the last
    // drink was logged (or now, if nothing's been logged), never before the start.
    // The user can dial in any exact moment via the date and time pickers; the
    // ViewModel still clamps the result to ≥ start.
    val zone = remember { ZoneId.systemDefault() }
    val defaultClose = remember(startedAtMillis, lastDrinkAtMillis) {
        val base = lastDrinkAtMillis?.plus(3_600_000L) ?: System.currentTimeMillis()
        base.coerceAtLeast(startedAtMillis)
    }
    var closeMillis by remember { mutableStateOf(defaultClose) }
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End session", style = Hf.type.headingSm) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Close time", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                Text(dateTimeLabel(closeMillis), style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecondaryPill(label = "Date", onClick = { showDatePicker = true })
                    SecondaryPill(label = "Time", onClick = { showTimePicker = true })
                }
            }
        },
        confirmButton = { PrimaryPill(label = "End", onClick = { onConfirm(closeMillis) }) },
        dismissButton = { SecondaryPill(label = "Cancel", onClick = onDismiss) },
    )

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = localDateToUtcMillis(closeMillis, zone),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                PrimaryPill(label = "Set", onClick = {
                    dateState.selectedDateMillis?.let { picked ->
                        closeMillis = combineDate(picked, closeMillis, zone)
                    }
                    showDatePicker = false
                })
            },
            dismissButton = {
                SecondaryPill(label = "Cancel", onClick = { showDatePicker = false })
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (showTimePicker) {
        val current = remember { Instant.ofEpochMilli(closeMillis).atZone(zone) }
        val timeState = rememberTimePickerState(
            initialHour = current.hour,
            initialMinute = current.minute,
            is24Hour = false,
        )
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Column(
                modifier = Modifier
                    .background(Hf.colors.canvas, RoundedCornerShape(16.dp))
                    .padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TimePicker(state = timeState)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryPill(label = "Cancel", onClick = { showTimePicker = false })
                    PrimaryPill(label = "Set", onClick = {
                        closeMillis = combineTime(closeMillis, timeState.hour, timeState.minute, zone)
                        showTimePicker = false
                    })
                }
            }
        }
    }
}

@Composable
private fun DrinkSummaryDialog(summary: DrinkSessionSummary, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session summary", style = Hf.type.headingSm) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SummaryRow("Drinks", summary.count.toString())
                SummaryRow("Standard drinks", formatStd(summary.standardDrinks))
                SummaryRow("Calories", formatKcal(summary.kcal))
                SummaryRow("Duration", formatDuration(summary.durationMillis))
            }
        },
        confirmButton = { PrimaryPill(label = "Done", onClick = onDismiss) },
    )
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
        Text(value, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
    }
}

@Composable
private fun PrimaryPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = Hf.type.capsSm,
        color = Hf.colors.textInverse,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Hf.colors.accent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun SecondaryPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = Hf.type.capsSm,
        color = Hf.colors.accent,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(0.5.dp, Hf.colors.borderStrong, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

// ---- formatting helpers (pure) ----

/** Standard drinks at 1 decimal (e.g. "1.5"). */
internal fun formatStd(value: Double): String = String.format(Locale.US, "%.1f", value)

/**
 * The secondary line under the headline (D14): sugar + carbs, plus the budget dent
 * only when a calorie target exists. `remainingBudget = target − loggedToday` (the
 * session kcal are already part of loggedToday via the mirror), floored at 0.
 */
internal fun secondaryLine(tally: DrinkTally, target: Double?, kcalLoggedToday: Double): String {
    val macros = "${formatGrams(tally.sugar)} sugar · ${formatGrams(tally.carbs)} carbs"
    if (target == null || target <= 0.0) return macros
    val remaining = (target - kcalLoggedToday).coerceAtLeast(0.0)
    return "$macros · ${formatKcal(remaining)} of today's budget"
}

private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.US)
private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d · h:mm a", Locale.US)

private fun startedLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(CLOCK)

private fun dateTimeLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DATE_TIME)

/**
 * The DatePicker works in UTC-midnight millis. Map a local instant to the UTC-midnight
 * millis of its local calendar date (to seed the picker) and back (to recombine a
 * picked date with the existing time-of-day), so the picked day is never off-by-one
 * across timezones.
 */
private fun localDateToUtcMillis(millis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun combineDate(pickedUtcMillis: Long, existing: Long, zone: ZoneId): Long {
    val date = Instant.ofEpochMilli(pickedUtcMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val time = Instant.ofEpochMilli(existing).atZone(zone).toLocalTime()
    return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}

private fun combineTime(existing: Long, hour: Int, minute: Int, zone: ZoneId): Long {
    val date = Instant.ofEpochMilli(existing).atZone(zone).toLocalDate()
    return date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()
}

internal fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60000L).coerceAtLeast(0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
