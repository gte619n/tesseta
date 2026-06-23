package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.MealSearchResult
import com.gte619n.healthfitness.domain.nutrition.derivedCaloriesKcal
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Add-food bottom sheet — one unified surface (no tabs):
 *  - the target meal is auto-inferred from the time of day, shown as a small
 *    tappable chip ("→ Lunch") that expands the meal picker to override;
 *  - one search field (inline trailing spinner while searching);
 *  - with an empty query the content is the RECENT list — one tap re-logs a
 *    recent food/meal with its previous portions;
 *  - with a query: catalog results, plus a standing "Log “…” as a meal" row
 *    that fires the fire-and-forget describe (the sheet closes immediately and
 *    the day shows the processing placeholder);
 *  - quick add (manual macros) tucked beneath; its calories are derived live
 *    from the macros (4/4/9) and not directly editable once a macro is typed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(
    onDismiss: () -> Unit,
    onAddCatalog: (Meal, Food, Int, Double) -> Unit,
    onAddQuick: (Meal, String, Macros) -> Unit,
    onDescribeAsync: (Meal, String) -> Unit,
    onRelogRecent: (Meal, Entry) -> Unit,
    onLogMeal: (Meal, MealSearchResult) -> Unit,
    viewModel: AddFoodViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var picked by remember { mutableStateOf<Food?>(null) }
    var meal by remember { mutableStateOf(Meal.forHour(LocalTime.now().hour)) }
    var mealPickerOpen by remember { mutableStateOf(false) }
    var quickMode by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Hf.colors.canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Add food", style = Hf.type.headingLg, color = Hf.colors.textPrimary)
                MealChip(meal = meal, onClick = { mealPickerOpen = !mealPickerOpen })
            }
            if (mealPickerOpen) {
                Spacer(Modifier.height(10.dp))
                MealPicker(
                    selected = meal,
                    onSelect = {
                        meal = it
                        mealPickerOpen = false
                    },
                )
            }
            Spacer(Modifier.height(12.dp))

            val selected = picked
            when {
                quickMode -> QuickAddForm(
                    onCancel = { quickMode = false },
                    onSave = { name, macros -> onAddQuick(meal, name, macros) },
                )
                selected != null -> FoodDetail(
                    food = selected,
                    onBack = { picked = null },
                    onSave = { idx, qty -> onAddCatalog(meal, selected, idx, qty) },
                )
                else -> SearchPane(
                    state = state,
                    meal = meal,
                    onQueryChange = viewModel::onQueryChange,
                    onPick = { picked = it },
                    onRelogRecent = { onRelogRecent(meal, it) },
                    onLogMeal = { onLogMeal(meal, it) },
                    onDescribe = { onDescribeAsync(meal, it) },
                    onQuickAdd = { quickMode = true },
                )
            }
        }
    }
}

/** "→ Lunch" — the auto-detected target meal; tap to override. */
@Composable
private fun MealChip(meal: Meal, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text("→ ${meal.label} ▾", style = Hf.type.bodyMd, color = Hf.colors.accentDim)
    }
}

@Composable
private fun SearchPane(
    state: AddFoodUiState,
    meal: Meal,
    onQueryChange: (String) -> Unit,
    onPick: (Food) -> Unit,
    onRelogRecent: (Entry) -> Unit,
    onLogMeal: (MealSearchResult) -> Unit,
    onDescribe: (String) -> Unit,
    onQuickAdd: () -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search foods or describe a meal…", style = Hf.type.bodyMd) },
        singleLine = true,
        trailingIcon = if (state.searching) {
            {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = Hf.colors.accent,
                    strokeWidth = 2.dp,
                )
            }
        } else {
            null
        },
    )
    Spacer(Modifier.height(10.dp))

    if (state.query.isBlank()) {
        RecentList(
            recents = state.recents,
            loading = state.recentsLoading,
            onRelog = onRelogRecent,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
                .clickable { onQuickAdd() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Quick add custom macros", style = Hf.type.bodyMd, color = Hf.colors.accentDim)
        }
        return
    }

    val screenH = LocalConfiguration.current.screenHeightDp.dp
    val noResults = state.results.isEmpty() && state.mealResults.isEmpty()
    when {
        state.error != null -> Text(state.error, style = Hf.type.bodyMd, color = Hf.colors.alert)
        // First search shows skeleton rows; later keystrokes keep the prior
        // results visible (with the inline spinner) to avoid flicker.
        state.searching && noResults -> SkeletonRows()
        !state.searching && noResults -> Text(
            "No matches.", style = Hf.type.bodyMd, color = Hf.colors.textTertiary,
        )
        // Saved meals first (full dishes the user logged before), then catalog
        // foods — combined into one scroll area to avoid nested scrolling.
        else -> LazyColumn(
            modifier = Modifier.heightIn(max = screenH * 0.45f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state.mealResults.isNotEmpty()) {
                item(key = "meals-header") {
                    Text("SAVED MEALS", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
                }
                items(state.mealResults, key = { "meal/${it.mealId}" }) { m ->
                    MealRow(meal = m, onClick = { onLogMeal(m) })
                }
            }
            if (state.results.isNotEmpty()) {
                if (state.mealResults.isNotEmpty()) {
                    item(key = "foods-header") {
                        Text("FOODS", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
                    }
                }
                items(state.results, key = { it.foodId }) { food ->
                    FoodRow(food = food, onClick = { onPick(food) })
                }
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    // The describe action rides along under the results: anything typed can be
    // logged as a described meal — the sheet closes immediately and the entry
    // resolves in the background (the camera-capture pattern).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .clickable { onDescribe(state.query) }
            .padding(vertical = 12.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "✨ Log “${state.query.trim()}” to ${meal.label} as a meal",
            style = Hf.type.bodyMd,
            color = Hf.colors.accentDim,
            maxLines = 1,
        )
    }
}

@Composable
private fun RecentList(
    recents: List<Entry>,
    loading: Boolean,
    onRelog: (Entry) -> Unit,
) {
    val screenH = LocalConfiguration.current.screenHeightDp.dp
    Text("RECENT", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
    Spacer(Modifier.height(8.dp))
    when {
        loading -> SkeletonRows()
        recents.isEmpty() -> Text(
            "Foods you log will show up here for one-tap repeats.",
            style = Hf.type.bodySm,
            color = Hf.colors.textTertiary,
        )
        else -> LazyColumn(
            modifier = Modifier.heightIn(max = screenH * 0.52f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(recents, key = { "${it.date}/${it.entryId}" }) { entry ->
                RecentRow(entry = entry, onClick = { onRelog(entry) })
            }
        }
    }
}

/** Placeholder rows shown while results / recents load (mirrors the web sheet). */
@Composable
private fun SkeletonRows(count: Int = 4) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) {
            HfCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(44.dp)
                            .background(Hf.colors.canvasMuted, RoundedCornerShape(8.dp)),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.5f)
                                .height(12.dp)
                                .background(Hf.colors.canvasMuted, RoundedCornerShape(4.dp)),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(0.25f)
                                .height(9.dp)
                                .background(Hf.colors.canvasMuted, RoundedCornerShape(4.dp)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(entry: Entry, onClick: () -> Unit) {
    HfCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoodThumbnail(imageUrl = entry.imageUrl, imageStatus = entry.imageStatus, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.foodName, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(formatKcal(entry.macros.caloriesKcal))
                        entry.servingLabel?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    },
                    style = Hf.type.monoSm,
                    color = Hf.colors.textSecondary,
                )
            }
            Text("+", style = Hf.type.headingMd, color = Hf.colors.accentDim)
        }
    }
}

@Composable
private fun MealRow(meal: MealSearchResult, onClick: () -> Unit) {
    HfCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoodThumbnail(imageUrl = meal.imageUrl, imageStatus = meal.imageStatus, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(meal.name, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(formatKcal(meal.macros.caloriesKcal))
                        meal.totalGrams?.let { append(" · ${it.roundToInt()} g") }
                    },
                    style = Hf.type.monoSm,
                    color = Hf.colors.textSecondary,
                )
            }
            Text("+", style = Hf.type.headingMd, color = Hf.colors.accentDim)
        }
    }
}

@Composable
private fun FoodRow(food: Food, onClick: () -> Unit) {
    HfCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoodThumbnail(imageUrl = food.imageUrl, imageStatus = food.imageStatus, size = 44.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(food.name, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                if (food.brand != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(food.brand!!, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "${formatKcal(food.macrosPer100g.caloriesKcal)} per 100 g",
                    style = Hf.type.monoSm,
                    color = Hf.colors.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun FoodDetail(
    food: Food,
    onBack: () -> Unit,
    onSave: (Int, Double) -> Unit,
) {
    var servingIndex by remember(food.foodId) {
        mutableStateOf(food.defaultServingIndex.coerceIn(0, (food.servingSizes.size - 1).coerceAtLeast(0)))
    }
    var quantity by remember(food.foodId) { mutableStateOf(1.0) }

    Column {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            FoodThumbnail(imageUrl = food.imageUrl, imageStatus = food.imageStatus, size = 52.dp)
            Column(modifier = Modifier.weight(1f)) {
                Text(food.name, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
                if (food.brand != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(food.brand!!, style = Hf.type.bodySm, color = Hf.colors.textTertiary)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        ServingAndQuantity(
            servingSizes = food.servingSizes,
            macrosPer100g = food.macrosPer100g,
            servingIndex = servingIndex,
            quantity = quantity,
            onServingChange = { servingIndex = it },
            onQuantityChange = { quantity = it },
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Back", Modifier.weight(1f), onBack)
            PrimaryButton("Add to log", Modifier.weight(1f)) { onSave(servingIndex, quantity) }
        }
    }
}

@Composable
private fun QuickAddForm(onCancel: () -> Unit, onSave: (String, Macros) -> Unit) {
    var name by remember { mutableStateOf("") }
    var manualKcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }

    // Calories are derived from the macros (4/4/9, matching the backend) the
    // moment any macro is typed; with no macros at all the user may enter
    // calories directly (a calories-only quick add stays possible).
    val derived = derivedCaloriesKcal(
        protein.toDoubleOrNull(),
        carbs.toDoubleOrNull(),
        fat.toDoubleOrNull(),
    )
    val hasMacros = protein.isNotBlank() || carbs.isNotBlank() || fat.isNotBlank()

    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Food name") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        NumberField("Protein (g)", protein) { protein = it }
        NumberField("Carbs (g)", carbs) { carbs = it }
        NumberField("Fat (g)", fat) { fat = it }
        if (hasMacros) {
            Spacer(Modifier.height(10.dp))
            Text(
                "= ${derived?.roundToInt() ?: 0} kcal (computed from macros)",
                style = Hf.type.monoSm,
                color = Hf.colors.textSecondary,
            )
        } else {
            NumberField("Calories (kcal) — or enter macros above", manualKcal) { manualKcal = it }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancel", Modifier.weight(1f), onCancel)
            PrimaryButton("Add to log", Modifier.weight(1f)) {
                onSave(
                    name.ifBlank { "Custom food" },
                    Macros(
                        caloriesKcal = if (hasMacros) derived else manualKcal.toDoubleOrNull(),
                        proteinGrams = protein.toDoubleOrNull(),
                        carbsGrams = carbs.toDoubleOrNull(),
                        fatGrams = fat.toDoubleOrNull(),
                    ),
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        placeholder = { Text(label) },
        singleLine = true,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = KeyboardType.Number,
        ),
    )
}

@Composable
internal fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(Hf.colors.accent, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Hf.type.capsSm, color = Hf.colors.textInverse)
    }
}

@Composable
internal fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Hf.type.capsSm, color = Hf.colors.textSecondary)
    }
}
