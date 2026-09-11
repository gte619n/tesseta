package com.gte619n.healthfitness.feature.settings.drinks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.LocalBar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.components.HfScreenHeader
import com.gte619n.healthfitness.ui.components.SectionTitle
import com.gte619n.healthfitness.ui.components.SettingsContentMaxWidth
import com.gte619n.healthfitness.ui.image.HfAsyncImage
import com.gte619n.healthfitness.ui.state.ErrorState
import com.gte619n.healthfitness.ui.state.LoadingState
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.util.Locale

/**
 * IMPL-DRINK-01 — Settings › Drinks: manage my personal drink catalog on the phone
 * (previously web-only). Lists my drinks with their generated glass image, lets me
 * add one at a time (name → analyze → review/edit → save), edit an existing drink,
 * regenerate its image, and archive it.
 */
@Composable
fun DrinkSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DrinkSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars)
            .background(Hf.colors.canvas),
    ) {
        HfScreenHeader(
            title = "Drinks",
            subtitle = "Manage your drink catalog",
            onBack = onNavigateBack,
            trailing = {
                Button(onClick = viewModel::openAdd) { Text("Add drink") }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = SettingsContentMaxWidth)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    state.loading && state.drinks.isEmpty() -> LoadingState()
                    state.error != null && state.drinks.isEmpty() ->
                        ErrorState(message = state.error!!, onRetry = viewModel::refresh)
                    state.drinks.isEmpty() ->
                        Text(
                            "No drinks yet — add one to log it during a session.",
                            style = Hf.type.bodySm,
                            color = Hf.colors.textTertiary,
                        )
                    else -> HfCard(transparent = true) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            SectionTitle("My drinks")
                            state.drinks.forEachIndexed { index, drink ->
                                DrinkRow(
                                    drink = drink,
                                    canMoveUp = index > 0,
                                    canMoveDown = index < state.drinks.lastIndex,
                                    onEdit = { viewModel.openEdit(drink) },
                                    onRegenerate = { viewModel.regenerateImage(drink) },
                                    onMoveUp = { viewModel.moveUp(drink) },
                                    onMoveDown = { viewModel.moveDown(drink) },
                                    onArchive = { viewModel.archive(drink) },
                                )
                            }
                        }
                    }
                }

                state.message?.let { msg ->
                    Text(msg, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                }
            }
        }
    }

    state.editor?.let { editor ->
        DrinkEditorDialog(
            editor = editor,
            onChange = viewModel::updateEditor,
            onAnalyze = viewModel::analyze,
            onSave = viewModel::save,
            onDismiss = viewModel::closeEditor,
        )
    }
}

@Composable
private fun DrinkRow(
    drink: Food,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onArchive: () -> Unit,
) {
    var showArchive by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DrinkThumb(drink)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                drink.name,
                style = Hf.type.bodyMd,
                color = Hf.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitleFor(drink),
                style = Hf.type.capsSm,
                color = Hf.colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Single ⋮ overflow menu (normal 48dp touch target), matching the nutrition
        // screen's convention, replacing the tiny inline Edit/Image/Archive controls.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "Drink actions",
                    tint = Hf.colors.textSecondary,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Edit", style = Hf.type.bodyMd, color = Hf.colors.textPrimary) },
                    onClick = { menuOpen = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text("Regenerate image", style = Hf.type.bodyMd, color = Hf.colors.textPrimary) },
                    onClick = { menuOpen = false; onRegenerate() },
                )
                if (canMoveUp) {
                    DropdownMenuItem(
                        text = { Text("Move up", style = Hf.type.bodyMd, color = Hf.colors.textPrimary) },
                        onClick = { menuOpen = false; onMoveUp() },
                    )
                }
                if (canMoveDown) {
                    DropdownMenuItem(
                        text = { Text("Move down", style = Hf.type.bodyMd, color = Hf.colors.textPrimary) },
                        onClick = { menuOpen = false; onMoveDown() },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Archive", style = Hf.type.bodyMd, color = Hf.colors.alert) },
                    onClick = { menuOpen = false; showArchive = true },
                )
            }
        }
    }

    if (showArchive) {
        AlertDialog(
            onDismissRequest = { showArchive = false },
            title = { Text("Archive ${drink.name}?", style = Hf.type.headingSm) },
            text = {
                Text(
                    "It will no longer appear in your drink list. Existing logs are kept.",
                    style = Hf.type.bodySm,
                    color = Hf.colors.textTertiary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showArchive = false
                    onArchive()
                }) { Text("Archive") }
            },
            dismissButton = {
                TextButton(onClick = { showArchive = false }) { Text("Cancel") }
            },
        )
    }
}

/** Generated glass image, or a glassware placeholder when it isn't READY (D12). */
@Composable
private fun DrinkThumb(drink: Food) {
    val shape = RoundedCornerShape(8.dp)
    if (drink.imageStatus == "READY" && drink.imageUrl != null) {
        HfAsyncImage(
            model = drink.imageUrl,
            contentDescription = drink.name,
            modifier = Modifier.size(48.dp).clip(shape),
        )
    } else {
        Box(
            modifier = Modifier.size(48.dp).clip(shape).background(Hf.colors.canvasSunken),
            contentAlignment = Alignment.Center,
        ) {
            if (drink.imageStatus == "PENDING") {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Outlined.LocalBar,
                    contentDescription = null,
                    tint = Hf.colors.textTertiary,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@Composable
private fun DrinkEditorDialog(
    editor: DrinkSettingsViewModel.EditorState,
    onChange: ((DrinkSettingsViewModel.EditorState) -> DrinkSettingsViewModel.EditorState) -> Unit,
    onAnalyze: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isNew = editor.drinkId == null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "Add a drink" else "Edit drink", style = Hf.type.headingSm) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = { v -> onChange { it.copy(name = v) } },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (isNew) {
                    OutlinedButton(
                        onClick = onAnalyze,
                        enabled = !editor.analyzing && editor.name.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (editor.analyzing) "Analyzing…" else "Analyze with AI")
                    }
                    if (editor.analyzeUnavailable) {
                        Text(
                            "Couldn't analyze — enter the details manually.",
                            style = Hf.type.bodySm,
                            color = Hf.colors.textTertiary,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        value = editor.abvPercent,
                        onValueChange = { v -> onChange { it.copy(abvPercent = v) } },
                        label = "ABV %",
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = editor.servingVolumeMl,
                        onValueChange = { v -> onChange { it.copy(servingVolumeMl = v) } },
                        label = "Volume (ml)",
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = editor.servingLabel,
                    onValueChange = { v -> onChange { it.copy(servingLabel = v) } },
                    label = { Text("Serving label (optional)") },
                    placeholder = { Text("e.g. 1 pint") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Mixer macros (per serving)", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        value = editor.carbsGrams,
                        onValueChange = { v -> onChange { it.copy(carbsGrams = v) } },
                        label = "Carbs (g)",
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        value = editor.sugarGrams,
                        onValueChange = { v -> onChange { it.copy(sugarGrams = v) } },
                        label = "Sugar (g)",
                        modifier = Modifier.weight(1f),
                    )
                }

                DerivedReadouts(editor)

                editor.error?.let { err ->
                    Text(err, style = Hf.type.bodySm, color = Hf.colors.alert)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = editor.canSave) {
                Text(if (editor.saving) "Saving…" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DerivedReadouts(editor: DrinkSettingsViewModel.EditorState) {
    if (editor.derivedAlcoholGrams == null &&
        editor.derivedStandardDrinks == null &&
        editor.derivedCaloriesKcal == null
    ) {
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        editor.derivedStandardDrinks?.let {
            ReadoutRow("Standard drinks", formatOneDp(it))
        }
        editor.derivedAlcoholGrams?.let {
            ReadoutRow("Alcohol", "${formatOneDp(it)} g")
        }
        editor.derivedCaloriesKcal?.let {
            ReadoutRow("Calories", "${it.toInt()} kcal")
        }
    }
}

@Composable
private fun ReadoutRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = Hf.type.capsSm, color = Hf.colors.textTertiary)
        Text(value, style = Hf.type.capsSm, color = Hf.colors.textSecondary)
    }
}

@Composable
private fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        // Numbers only: digits + a single decimal point.
        onValueChange = { v -> onValueChange(v.filter { it.isDigit() || it == '.' }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}

private fun subtitleFor(drink: Food): String {
    val a = drink.alcohol
    val parts = buildList {
        a?.abvPercent?.let { add("${formatOneDp(it)}% ABV") }
        a?.servingVolumeMl?.let { add("${it.toInt()} ml") }
        a?.standardDrinks?.let { add("${formatOneDp(it)} std") }
        drink.servingMacros?.caloriesKcal?.let { add("${it.toInt()} kcal") }
    }
    return if (parts.isEmpty()) "Drink" else parts.joinToString(" · ")
}

private fun formatOneDp(value: Double): String = String.format(Locale.US, "%.1f", value)
