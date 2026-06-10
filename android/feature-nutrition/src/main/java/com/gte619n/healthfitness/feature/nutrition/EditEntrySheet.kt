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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
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
import com.gte619n.healthfitness.domain.nutrition.EntryPatchRequest
import com.gte619n.healthfitness.domain.nutrition.Macros
import com.gte619n.healthfitness.domain.nutrition.Meal
import com.gte619n.healthfitness.domain.nutrition.derivedCaloriesKcal
import com.gte619n.healthfitness.domain.nutrition.forPortion
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import kotlin.math.roundToInt

/**
 * Edit-entry bottom sheet. Lets the user change the meal, serving, quantity and
 * macros of an already-logged food. Changing serving grams or quantity re-scales
 * the macros from the per-100g baseline implied by the entry's frozen snapshot
 * (same maths as the add flow); each macro is still individually editable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditEntrySheet(
    entry: Entry,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, EntryPatchRequest) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val per100g = remember(entry.entryId) { entry.derivedPer100g() }

    var name by remember(entry.entryId) { mutableStateOf(entry.foodName) }
    var meal by remember(entry.entryId) {
        mutableStateOf(Meal.entries.firstOrNull { it.wire == entry.meal } ?: Meal.BREAKFAST)
    }
    var servingLabel by remember(entry.entryId) { mutableStateOf(entry.servingLabel.orEmpty()) }
    var servingGrams by remember(entry.entryId) { mutableStateOf(trimDouble(entry.servingGrams ?: 0.0)) }
    var quantity by remember(entry.entryId) { mutableStateOf(entry.quantity) }
    var quantityText by remember(entry.entryId) { mutableStateOf(trimDouble(entry.quantity)) }
    var manualKcal by remember(entry.entryId) { mutableStateOf(macroStr(entry.macros.caloriesKcal)) }
    var protein by remember(entry.entryId) { mutableStateOf(macroStr(entry.macros.proteinGrams)) }
    var carbs by remember(entry.entryId) { mutableStateOf(macroStr(entry.macros.carbsGrams)) }
    var fat by remember(entry.entryId) { mutableStateOf(macroStr(entry.macros.fatGrams)) }
    var fiber by remember(entry.entryId) { mutableStateOf(macroStr(entry.macros.fiberGrams)) }
    var sugar by remember(entry.entryId) { mutableStateOf(macroStr(entry.macros.sugarGrams)) }

    // Calories follow the macros (4/4/9, the backend's invariant) whenever any
    // macro is present; only a macro-less (calories-only) entry keeps a manually
    // editable calories field.
    val hasMacros = protein.isNotBlank() || carbs.isNotBlank() || fat.isNotBlank()
    val derivedKcal = derivedCaloriesKcal(
        protein.toDoubleOrNull(),
        carbs.toDoubleOrNull(),
        fat.toDoubleOrNull(),
    )
    val kcal = if (hasMacros) macroStr(derivedKcal) else manualKcal

    fun rescale(gramsStr: String, qty: Double) {
        val grams = gramsStr.toDoubleOrNull() ?: return
        if (grams <= 0.0) return
        val scaled = per100g.forPortion(grams, qty)
        manualKcal = macroStr(scaled.caloriesKcal)
        protein = macroStr(scaled.proteinGrams)
        carbs = macroStr(scaled.carbsGrams)
        fat = macroStr(scaled.fatGrams)
        fiber = macroStr(scaled.fiberGrams)
        sugar = macroStr(scaled.sugarGrams)
    }

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
            // ── Hero: larger image + live amount/calorie readout ──────────
            val effectiveGrams = (servingGrams.toDoubleOrNull() ?: 0.0) * quantity
            val liveKcal = kcal.toDoubleOrNull()?.roundToInt()
            Row(verticalAlignment = Alignment.CenterVertically) {
                FoodThumbnail(imageUrl = entry.imageUrl, imageStatus = entry.imageStatus, size = 76.dp)
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name.ifBlank { entry.foodName },
                        style = Hf.type.headingMd,
                        color = Hf.colors.textPrimary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        buildString {
                            append("${effectiveGrams.roundToInt()} g")
                            if (liveKcal != null) append(" · $liveKcal kcal")
                        },
                        style = Hf.type.monoSm,
                        color = Hf.colors.textSecondary,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title") },
                singleLine = true,
            )
            Spacer(Modifier.height(14.dp))

            Text("Meal", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
            Spacer(Modifier.height(5.dp))
            MealPicker(selected = meal, onSelect = { meal = it })
            Spacer(Modifier.height(16.dp))

            // ── Amount: serving label + grams, quantity chips + custom entry ─
            Text("Amount", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
            Spacer(Modifier.height(5.dp))
            OutlinedTextField(
                value = servingLabel,
                onValueChange = { servingLabel = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Serving (e.g. 1 container, 1 slice, 100 g)") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = servingGrams,
                    onValueChange = {
                        servingGrams = it
                        rescale(it, quantity)
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Grams / serving") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = quantityText,
                    onValueChange = { text ->
                        quantityText = text
                        text.toDoubleOrNull()?.takeIf { it > 0 }?.let { q ->
                            quantity = q
                            rescale(servingGrams, q)
                        }
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Quantity (×)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(8.dp))
            ChipRow(
                options = QUANTITY_STEPS,
                selected = quantity,
                label = { "${trimDouble(it)}×" },
                onSelect = {
                    quantity = it
                    quantityText = trimDouble(it)
                    rescale(servingGrams, it)
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "= ${effectiveGrams.roundToInt()} g total" +
                    (liveKcal?.let { " · $it kcal" } ?: ""),
                style = Hf.type.bodySm,
                color = Hf.colors.textTertiary,
            )
            Spacer(Modifier.height(16.dp))

            // ── Macros: calories derived from them (4/4/9), then two-up rows ──
            Text("Macros", style = Hf.type.capsSm, color = Hf.colors.textTertiary)
            if (hasMacros) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Calories: ${derivedKcal?.roundToInt() ?: 0} kcal — computed from macros",
                    style = Hf.type.monoSm,
                    color = Hf.colors.textSecondary,
                )
            } else {
                EditNumberField("Calories (kcal)", manualKcal, Modifier.fillMaxWidth()) { manualKcal = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EditNumberField("Protein (g)", protein, Modifier.weight(1f)) { protein = it }
                EditNumberField("Carbs (g)", carbs, Modifier.weight(1f)) { carbs = it }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                EditNumberField("Fat (g)", fat, Modifier.weight(1f)) { fat = it }
                EditNumberField("Sugar (g)", sugar, Modifier.weight(1f)) { sugar = it }
            }
            EditNumberField("Fiber (g)", fiber, Modifier.fillMaxWidth()) { fiber = it }
            Spacer(Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onDismiss)
                PrimaryButton(
                    if (saving) "Saving…" else "Save changes",
                    Modifier.weight(1f),
                ) {
                    if (saving) return@PrimaryButton
                    val grams = servingGrams.toDoubleOrNull()?.takeIf { it > 0 } ?: entry.servingGrams
                    onSave(
                        entry.entryId,
                        EntryPatchRequest(
                            meal = meal.wire,
                            foodName = name.ifBlank { entry.foodName },
                            servingLabel = servingLabel.ifBlank { entry.servingLabel.orEmpty() },
                            servingGrams = grams,
                            quantity = quantity,
                            macros = Macros(
                                caloriesKcal = kcal.toDoubleOrNull(),
                                proteinGrams = protein.toDoubleOrNull(),
                                carbsGrams = carbs.toDoubleOrNull(),
                                fatGrams = fat.toDoubleOrNull(),
                                fiberGrams = fiber.toDoubleOrNull(),
                                sugarGrams = sugar.toDoubleOrNull(),
                            ),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditNumberField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.padding(top = 8.dp),
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

/** Back out the per-100g baseline implied by a snapshot + its portion. */
private fun Entry.derivedPer100g(): Macros {
    val factor = ((servingGrams ?: 0.0) * quantity) / 100.0
    fun back(v: Double?): Double? = v?.let { if (factor > 0) it / factor else it }
    return Macros(
        caloriesKcal = back(macros.caloriesKcal),
        proteinGrams = back(macros.proteinGrams),
        carbsGrams = back(macros.carbsGrams),
        fatGrams = back(macros.fatGrams),
        fiberGrams = back(macros.fiberGrams),
        sugarGrams = back(macros.sugarGrams),
    )
}

private fun macroStr(v: Double?): String =
    if (v == null) "" else (Math.round(v * 10.0) / 10.0).let { trimDouble(it) }

private fun trimDouble(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
