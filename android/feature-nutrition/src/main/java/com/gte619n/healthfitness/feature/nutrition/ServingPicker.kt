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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.ServingSize
import com.gte619n.healthfitness.domain.nutrition.forPortion
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/** The four allowed quantity steps (simple portions). */
val QUANTITY_STEPS = listOf(0.5, 1.0, 1.5, 2.0)

/** A horizontal row of selectable chips. */
@Composable
fun <T> ChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (active) Hf.colors.accentBg else Hf.colors.surface,
                        RoundedCornerShape(7.dp),
                    )
                    .border(
                        0.5.dp,
                        if (active) Hf.colors.accent else Hf.colors.borderDefault,
                        RoundedCornerShape(7.dp),
                    )
                    .clickable { onSelect(option) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = Hf.type.bodyMd,
                    color = if (active) Hf.colors.accentDim else Hf.colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Meal selector chips (Breakfast/Lunch/Dinner/Snack). */
@Composable
fun MealPicker(selected: Meal, onSelect: (Meal) -> Unit, modifier: Modifier = Modifier) {
    ChipRow(
        options = Meal.entries,
        selected = selected,
        label = { it.label },
        onSelect = onSelect,
        modifier = modifier,
    )
}

/**
 * Serving + quantity picker with a live macro preview, given the food's
 * per-100g macros and its serving sizes. Reports the chosen index + quantity.
 */
@Composable
fun ServingAndQuantity(
    servingSizes: List<ServingSize>,
    macrosPer100g: Macros,
    servingIndex: Int,
    quantity: Double,
    onServingChange: (Int) -> Unit,
    onQuantityChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val serving = servingSizes.getOrNull(servingIndex)
    Column(modifier = modifier) {
        if (servingSizes.isNotEmpty()) {
            Text("Serving", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
            Spacer(Modifier.height(5.dp))
            ChipRow(
                options = servingSizes.indices.toList(),
                selected = servingIndex.coerceIn(0, servingSizes.size - 1),
                label = { servingSizes[it].label },
                onSelect = onServingChange,
            )
            Spacer(Modifier.height(11.dp))
        }
        Text("Quantity", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
        Spacer(Modifier.height(5.dp))
        ChipRow(
            options = QUANTITY_STEPS,
            selected = quantity,
            label = { "${it}×" },
            onSelect = onQuantityChange,
        )
        if (serving != null) {
            Spacer(Modifier.height(12.dp))
            MacroPreview(macros = macrosPer100g.forPortion(serving.grams, quantity))
        }
    }
}

/** Compact one-line macro readout used in the picker and capture flows. */
@Composable
fun MacroPreview(macros: Macros, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MacroChip("KCAL", formatKcal(macros.caloriesKcal))
        MacroChip("P", formatGrams(macros.proteinGrams))
        MacroChip("C", formatGrams(macros.carbsGrams))
        MacroChip("F", formatGrams(macros.fatGrams))
    }
}

@Composable
private fun MacroChip(label: String, value: String) {
    Column {
        Text(label, style = Hf.type.capsSm, color = Hf.colors.textTertiary)
        Spacer(Modifier.height(2.dp))
        Text(value, style = Hf.type.monoSm, color = Hf.colors.textPrimary)
    }
}
