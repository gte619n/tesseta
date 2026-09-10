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
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
            onDismissSummary = viewModel::dismissSummary,
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
    onDismissSummary: () -> Unit,
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

    state.summary?.let { summary ->
        DrinkSummaryDialog(summary = summary, onDismiss = onDismissSummary)
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

@Composable
private fun EndSessionDialog(
    startedAtMillis: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    // D21: an editable close time (default now, must be ≥ start). Kept simple: the
    // user nudges the close time back in 15-min steps from now; it can never go
    // below start. Only the summary duration is affected.
    val now = System.currentTimeMillis()
    var minutesBack by remember { mutableStateOf(0) }
    val maxBack = (((now - startedAtMillis) / 60000L).toInt()).coerceAtLeast(0)
    val close = (now - minutesBack * 60000L).coerceAtLeast(startedAtMillis)

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End session", style = Hf.type.headingSm) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Close time", style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                Text(clockLabel(close), style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecondaryPill(
                        label = "−15 min",
                        onClick = { minutesBack = (minutesBack + 15).coerceAtMost(maxBack) },
                    )
                    SecondaryPill(label = "Now", onClick = { minutesBack = 0 })
                }
            }
        },
        confirmButton = { PrimaryPill(label = "End", onClick = { onConfirm(close) }) },
        dismissButton = { SecondaryPill(label = "Cancel", onClick = onDismiss) },
    )
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
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

private fun startedLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(CLOCK)

private fun clockLabel(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(TIME_ONLY)

internal fun formatDuration(millis: Long): String {
    val totalMinutes = (millis / 60000L).coerceAtLeast(0)
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
