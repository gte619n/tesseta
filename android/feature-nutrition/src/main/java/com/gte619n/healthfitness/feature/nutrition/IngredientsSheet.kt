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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.nutrition.Entry
import com.gte619n.healthfitness.domain.nutrition.EntryIngredient
import com.gte619n.healthfitness.domain.nutrition.UpdateIngredientRequest
import com.gte619n.healthfitness.domain.nutrition.forPortion
import com.gte619n.healthfitness.ui.components.HfCard
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.roundToInt

/**
 * Bottom sheet for a composite (photo-logged) meal: shows the finished-meal
 * image and each ingredient with its own raw-ingredient image and an editable
 * portion. Editing a portion re-scales that ingredient from its per-100g
 * baseline server-side and the meal total recomputes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IngredientsSheet(
    entry: Entry,
    saving: Boolean,
    onDismiss: () -> Unit,
    onUpdateIngredient: (index: Int, UpdateIngredientRequest) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val ingredients = entry.ingredients.orEmpty()

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
            // ── Hero: finished-meal image + name + total calories ─────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                FoodThumbnail(imageUrl = entry.imageUrl, imageStatus = entry.imageStatus, size = 76.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.foodName, style = Hf.type.headingMd, color = Hf.colors.textPrimary)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(entry.macros.caloriesKcal ?: 0.0).roundToInt()} kcal · " +
                            "${ingredients.size} ingredients",
                        style = Hf.type.monoSm,
                        color = Hf.colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            Text("Ingredients", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
            Spacer(Modifier.height(8.dp))
            ingredients.forEachIndexed { index, ingredient ->
                IngredientCard(
                    ingredient = ingredient,
                    saving = saving,
                    onSave = { grams, qty ->
                        onUpdateIngredient(
                            index,
                            UpdateIngredientRequest(
                                servingGrams = grams,
                                quantity = qty,
                                servingLabel = ingredient.servingLabel,
                            ),
                        )
                    },
                )
                Spacer(Modifier.height(10.dp))
            }
            Spacer(Modifier.height(6.dp))
            SecondaryButton("Done", Modifier.fillMaxWidth(), onDismiss)
        }
    }
}

@Composable
private fun IngredientCard(
    ingredient: EntryIngredient,
    saving: Boolean,
    onSave: (grams: Double, quantity: Double) -> Unit,
) {
    var gramsText by remember(ingredient) {
        mutableStateOf(trimAmount(ingredient.servingGrams ?: 0.0))
    }
    var qtyText by remember(ingredient) {
        mutableStateOf(trimAmount(ingredient.quantity ?: 1.0))
    }

    val grams = gramsText.toDoubleOrNull() ?: 0.0
    val qty = qtyText.toDoubleOrNull() ?: 1.0
    // Live macro preview from the per-100g baseline (falls back to stored macros).
    val preview = ingredient.macrosPer100g?.forPortion(grams, qty) ?: ingredient.macros
    val kcal = (preview.caloriesKcal ?: 0.0).roundToInt()

    HfCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FoodThumbnail(imageUrl = ingredient.imageUrl, imageStatus = ingredient.imageStatus, size = 48.dp)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(ingredient.name, style = Hf.type.headingSm, color = Hf.colors.textPrimary)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${(grams * qty).roundToInt()} g · $kcal kcal",
                        style = Hf.type.capsSm,
                        color = Hf.colors.textTertiary,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = gramsText,
                    onValueChange = { gramsText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Grams") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = qtyText,
                    onValueChange = { qtyText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Quantity (×)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(8.dp))
            PrimaryButton(
                if (saving) "Saving…" else "Update portion",
                Modifier.fillMaxWidth(),
            ) {
                if (saving) return@PrimaryButton
                val g = gramsText.toDoubleOrNull()?.takeIf { it > 0 } ?: return@PrimaryButton
                val q = qtyText.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
                onSave(g, q)
            }
        }
    }
}

private fun trimAmount(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
