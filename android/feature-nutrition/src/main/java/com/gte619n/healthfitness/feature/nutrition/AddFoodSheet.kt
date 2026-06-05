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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gte619n.healthfitness.domain.nutrition.Food
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Add-food bottom sheet. Two paths: catalog search → pick → serving/qty, or a
 * "Quick add" of ad-hoc macros. Saving is delegated to the caller (the Today
 * ViewModel owns the date/entry write).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFoodSheet(
    onDismiss: () -> Unit,
    onAddCatalog: (Meal, Food, Int, Double) -> Unit,
    onAddQuick: (Meal, String, Macros) -> Unit,
    onLogDescribed: (Meal, String) -> Unit,
    viewModel: AddFoodViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var picked by remember { mutableStateOf<Food?>(null) }
    var meal by remember { mutableStateOf(Meal.BREAKFAST) }
    var quickMode by remember { mutableStateOf(false) }
    var describeMode by remember { mutableStateOf(false) }

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
            Text("Add food", style = Hf.type.headingLg, color = Hf.colors.textPrimary)
            Spacer(Modifier.height(10.dp))
            MealPicker(selected = meal, onSelect = { meal = it })
            Spacer(Modifier.height(14.dp))

            val selected = picked
            when {
                describeMode -> DescribeForm(
                    state = state,
                    meal = meal,
                    onDescribe = viewModel::describe,
                    onEditDescription = viewModel::clearDescribed,
                    onLog = { mealId -> onLogDescribed(meal, mealId) },
                    onCancel = {
                        viewModel.clearDescribed()
                        describeMode = false
                    },
                )
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
                    onQueryChange = viewModel::onQueryChange,
                    onPick = { picked = it },
                    onQuickAdd = { quickMode = true },
                    onDescribe = { describeMode = true },
                )
            }
        }
    }
}

@Composable
private fun SearchPane(
    state: AddFoodUiState,
    onQueryChange: (String) -> Unit,
    onPick: (Food) -> Unit,
    onQuickAdd: () -> Unit,
    onDescribe: () -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search foods…", style = Hf.type.bodyMd) },
        singleLine = true,
    )
    Spacer(Modifier.height(10.dp))
    when {
        state.searching -> Box(
            Modifier.fillMaxWidth().padding(20.dp),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = Hf.colors.accent) }
        state.error != null -> Text(state.error, style = Hf.type.bodyMd, color = Hf.colors.alert)
        state.query.isNotBlank() && state.results.isEmpty() -> Text(
            "No matches.", style = Hf.type.bodyMd, color = Hf.colors.textTertiary,
        )
        else -> LazyColumn(
            modifier = Modifier.heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.results, key = { it.foodId }) { food ->
                FoodRow(food = food, onClick = { onPick(food) })
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .clickable { onDescribe() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text("Describe a meal", style = Hf.type.bodyMd, color = Hf.colors.accentDim)
    }
    Spacer(Modifier.height(8.dp))
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
}

/**
 * Describe-a-meal flow. Stage 1: a free-text field → resolve to a saved meal
 * (a previous match, or a freshly created one). Stage 2: preview the resolved
 * meal (matched/new, macros, ingredients, photo) → log it onto the day.
 */
@Composable
private fun DescribeForm(
    state: AddFoodUiState,
    meal: Meal,
    onDescribe: (String) -> Unit,
    onEditDescription: () -> Unit,
    onLog: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var description by remember { mutableStateOf("") }
    val resolved = state.described

    if (resolved != null) {
        Column {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                FoodThumbnail(
                    imageUrl = resolved.imageUrl,
                    imageStatus = resolved.imageStatus,
                    size = 52.dp,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(resolved.name, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (resolved.matched) "Previous meal — reusing its macros & photo"
                        else "New meal — photo generating now",
                        style = Hf.type.bodySm,
                        color = Hf.colors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "${formatKcal(resolved.macros.caloriesKcal)} · " +
                    "P ${formatGrams(resolved.macros.proteinGrams)} · " +
                    "C ${formatGrams(resolved.macros.carbsGrams)} · " +
                    "F ${formatGrams(resolved.macros.fatGrams)}",
                style = Hf.type.monoSm,
                color = Hf.colors.textSecondary,
            )
            if (resolved.ingredients.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(resolved.ingredients, key = { it.name }) { ing ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(ing.name, style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
                            Text(
                                formatKcal(ing.macros.caloriesKcal),
                                style = Hf.type.monoSm,
                                color = Hf.colors.textTertiary,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Edit", Modifier.weight(1f), onEditDescription)
                PrimaryButton("Add to ${meal.label}", Modifier.weight(1f)) {
                    onLog(resolved.mealId)
                }
            }
        }
        return
    }

    Column {
        Text(
            "Describe what you ate — we'll find a meal you've logged before, or " +
                "create one with estimated macros and a matching photo.",
            style = Hf.type.bodyMd,
            color = Hf.colors.textSecondary,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("e.g. grilled chicken with rice and broccoli") },
            minLines = 2,
        )
        if (state.describeError != null) {
            Spacer(Modifier.height(8.dp))
            Text(state.describeError, style = Hf.type.bodyMd, color = Hf.colors.alert)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancel", Modifier.weight(1f), onCancel)
            if (state.describing) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Hf.colors.accent, RoundedCornerShape(8.dp))
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(16.dp),
                        color = Hf.colors.textInverse,
                        strokeWidth = 2.dp,
                    )
                }
            } else {
                PrimaryButton("Find or create", Modifier.weight(1f)) { onDescribe(description) }
            }
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
    var kcal by remember { mutableStateOf("") }
    var protein by remember { mutableStateOf("") }
    var carbs by remember { mutableStateOf("") }
    var fat by remember { mutableStateOf("") }

    Column {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Food name") },
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        NumberField("Calories (kcal)", kcal) { kcal = it }
        NumberField("Protein (g)", protein) { protein = it }
        NumberField("Carbs (g)", carbs) { carbs = it }
        NumberField("Fat (g)", fat) { fat = it }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancel", Modifier.weight(1f), onCancel)
            PrimaryButton("Add to log", Modifier.weight(1f)) {
                onSave(
                    name.ifBlank { "Custom food" },
                    Macros(
                        caloriesKcal = kcal.toDoubleOrNull(),
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
