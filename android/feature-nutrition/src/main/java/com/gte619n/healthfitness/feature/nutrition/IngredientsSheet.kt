package com.gte619n.healthfitness.feature.nutrition

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryIngredient
import com.gte619n.healthfitness.domain.nutrition.forPortion
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.roundToInt

/**
 * Bottom sheet for a composite (photo-logged) meal: the finished-meal image, an
 * editable meal title, and each ingredient with its raw-ingredient image and a
 * quantity multiplier. The portion size itself isn't editable — only how many of
 * it. A "portion of meal" multiplier (the entry's own quantity) scales the whole
 * meal's macros at once — pick ½ when you split a meal. It's stored per entry, so
 * the same meal logged twice can carry different portions. One Save commits the
 * title, the portion and any ingredient quantity changes together.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientsSheet(
    entry: Entry,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (title: String, portion: Double, quantities: List<Double>) -> Unit,
    // Lazy "typical serving" explanation for the whole meal, fetched on open.
    fetchServingHint: suspend (String) -> String? = { null },
    // "Adjust with AI": preview a free-text correction, then apply it on confirm.
    previewAdjustment: suspend (String) -> com.gte619n.healthfitness.domain.nutrition.AdjustPreviewResponse,
    onApplyAdjustment: (com.gte619n.healthfitness.domain.nutrition.AdjustApplyRequest) -> Unit,
    applyingAdjustment: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ingredients = entry.ingredients.orEmpty()
    // Generated + cached server-side on first view; absent for un-synced rows.
    val servingHint by produceState<String?>(initialValue = null, entry.entryId) {
        value = fetchServingHint(entry.entryId)
    }
    var title by remember(entry.entryId) { mutableStateOf(entry.foodName) }
    val quantities = remember(entry.entryId) {
        mutableStateListOf(*ingredients.map { trimAmount(it.quantity ?: 1.0) }.toTypedArray())
    }
    // Whole-meal portion = the entry's own quantity, persisted per entry. Scales
    // the displayed total; the ingredients keep their full-recipe amounts.
    var portion by remember(entry.entryId) { mutableStateOf(entry.quantity) }
    var portionText by remember(entry.entryId) { mutableStateOf(trimAmount(entry.quantity)) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Hf.colors.canvas,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
        ) {
            // Live total reflects per-ingredient edits and the meal portion: the
            // full-recipe ingredient sum, scaled once by the whole-meal portion.
            val recipeKcal = ingredients.foldIndexed(0.0) { index, sum, ing ->
                val qty = quantities.getOrNull(index)?.toDoubleOrNull() ?: 1.0
                val preview = ing.macrosPer100g?.forPortion(ing.servingGrams ?: 0.0, qty) ?: ing.macros
                sum + (preview.caloriesKcal ?: 0.0)
            }
            val liveKcal = recipeKcal * portion

            // ── Hero: finished-meal image + total calories ────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                FoodThumbnail(imageUrl = entry.imageUrl, imageStatus = entry.imageStatus, size = 76.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { entry.foodName },
                        style = Hf.type.headingMd,
                        color = Hf.colors.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${liveKcal.roundToInt()} kcal · ${ingredients.size} ingredients",
                        style = Hf.type.monoSm,
                        color = Hf.colors.textSecondary,
                    )
                }
            }
            // Generated everyday-terms explanation of the meal's portion so the
            // amount is easy to picture. Lazy + best-effort; shown once it lands.
            servingHint?.takeIf { it.isNotBlank() }?.let { hint ->
                Spacer(Modifier.height(10.dp))
                Text(hint, style = Hf.type.bodySm, color = Hf.colors.textSecondary)
            }
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Meal title") },
                singleLine = true,
            )
            Spacer(Modifier.height(16.dp))

            // ── Portion of the whole meal — scales every ingredient at once ─
            Text("Portion of meal", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
            Spacer(Modifier.height(5.dp))
            ChipRow(
                options = QUANTITY_STEPS,
                selected = portion,
                label = { "${trimAmount(it)}×" },
                onSelect = {
                    portion = it
                    portionText = trimAmount(it)
                },
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = portionText,
                onValueChange = { text ->
                    portionText = text
                    text.toDoubleOrNull()?.takeIf { it > 0 }?.let { portion = it }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Portion (×)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(16.dp))

            Text("Ingredients", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
            Spacer(Modifier.height(8.dp))
            ingredients.forEachIndexed { index, ingredient ->
                IngredientCard(
                    ingredient = ingredient,
                    quantity = quantities.getOrElse(index) { "1" },
                    onQuantityChange = { quantities[index] = it },
                )
                Spacer(Modifier.height(10.dp))
            }

            Spacer(Modifier.height(14.dp))
            AdjustWithAiSection(
                isComposite = true,
                previewAdjustment = previewAdjustment,
                onApply = onApplyAdjustment,
                applying = applyingAdjustment,
            )

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onDismiss)
                PrimaryButton(
                    if (saving) "Saving…" else "Save",
                    Modifier.weight(1f),
                ) {
                    if (saving) return@PrimaryButton
                    onSave(
                        title,
                        portion.takeIf { it > 0 } ?: 1.0,
                        quantities.map { it.toDoubleOrNull()?.takeIf { q -> q > 0 } ?: 1.0 },
                    )
                }
            }
        }
    }
}

@Composable
private fun IngredientCard(
    ingredient: EntryIngredient,
    quantity: String,
    onQuantityChange: (String) -> Unit,
) {
    val baseGrams = ingredient.servingGrams ?: 0.0
    val qty = quantity.toDoubleOrNull() ?: 1.0
    // Portion size is fixed; the quantity multiplier scales it.
    val preview = ingredient.macrosPer100g?.forPortion(baseGrams, qty) ?: ingredient.macros
    val kcal = (preview.caloriesKcal ?: 0.0).roundToInt()

    HfCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FoodThumbnail(imageUrl = ingredient.imageUrl, imageStatus = ingredient.imageStatus, size = 48.dp)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(ingredient.name, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    "${(baseGrams * qty).roundToInt()} g · $kcal kcal",
                    style = Hf.type.capsSm,
                    color = Hf.colors.textTertiary,
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = quantity,
                onValueChange = onQuantityChange,
                modifier = Modifier.width(96.dp),
                label = { Text("Qty ×") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
    }
}

private fun trimAmount(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
