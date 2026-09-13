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
import androidx.compose.runtime.produceState
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
import com.gte619n.healthfitness.ui.format.formatWholeNumber
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

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
    // Lazy "typical serving" explanation, fetched once when the sheet opens.
    // Returns null when unavailable, in which case no hint line is shown.
    fetchServingHint: suspend (String) -> String? = { null },
    // "Adjust with AI" (async): submit a free-text correction; review when ready.
    onSubmitAdjust: (instruction: String, saveAsMeal: Boolean) -> Unit,
    onReviewAdjust: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Generated + cached server-side on first view; absent for un-synced rows.
    val servingHint by produceState<String?>(initialValue = null, entry.entryId) {
        value = fetchServingHint(entry.entryId)
    }
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
            val liveKcal = kcal.toDoubleOrNull()?.let { formatWholeNumber(it) }
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
                            append("${formatWholeNumber(effectiveGrams)} g")
                            if (liveKcal != null) append(" · $liveKcal kcal")
                        },
                        style = Hf.type.monoSm,
                        color = Hf.colors.textSecondary,
                    )
                }
            }
            // A generated, everyday-terms explanation of the portion so the amount
            // is easy to picture (e.g. "About ¾ cup of blueberries (110 g)"). Lazy
            // and best-effort: shown only once it lands, never blocks the sheet.
            servingHint?.takeIf { it.isNotBlank() }?.let { hint ->
                Spacer(Modifier.height(10.dp))
                Text(
                    hint,
                    style = Hf.type.bodySm,
                    color = Hf.colors.textSecondary,
                )
            }
            SheetSection("Details") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title") },
                    singleLine = true,
                )
                MealPicker(selected = meal, onSelect = { meal = it })
            }

            SheetSection("Amount") {
                OutlinedTextField(
                    value = servingLabel,
                    onValueChange = { servingLabel = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Serving (e.g. 1 container, 1 slice, 100 g)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditNumberField("Grams / serving", servingGrams, Modifier.weight(1f)) {
                        servingGrams = it
                        rescale(it, quantity)
                    }
                    EditNumberField("Quantity (×)", quantityText, Modifier.weight(1f)) { text ->
                        quantityText = text
                        text.toDoubleOrNull()?.takeIf { it > 0 }?.let { q ->
                            quantity = q
                            rescale(servingGrams, q)
                        }
                    }
                }
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
                Text(
                    "= ${formatWholeNumber(effectiveGrams)} g total" +
                        (liveKcal?.let { " · $it kcal" } ?: ""),
                    style = Hf.type.monoSm,
                    color = Hf.colors.textTertiary,
                )
            }

            // Calories follow the macros (4/4/9); only a macro-less entry edits them.
            SheetSection("Macros") {
                if (hasMacros) {
                    Text(
                        "Calories ${derivedKcal?.let { formatWholeNumber(it) } ?: "0"} kcal · computed from macros",
                        style = Hf.type.monoSm,
                        color = Hf.colors.textSecondary,
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        EditNumberField("Calories (kcal)", manualKcal, Modifier.weight(1f)) { manualKcal = it }
                        Spacer(Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditNumberField("Protein (g)", protein, Modifier.weight(1f)) { protein = it }
                    EditNumberField("Carbs (g)", carbs, Modifier.weight(1f)) { carbs = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditNumberField("Fat (g)", fat, Modifier.weight(1f)) { fat = it }
                    EditNumberField("Sugar (g)", sugar, Modifier.weight(1f)) { sugar = it }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    EditNumberField("Fiber (g)", fiber, Modifier.weight(1f)) { fiber = it }
                    Spacer(Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(18.dp))
            AdjustWithAiSection(
                entry = entry,
                isComposite = false,
                onSubmitAdjust = onSubmitAdjust,
                onReviewAdjust = onReviewAdjust,
            )

            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Cancel", Modifier.weight(1f), onDismiss)
                PrimaryButton(
                    if (saving) "Saving…" else "Save",
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
    // No baked-in padding — the section's spacedBy rhythm owns the spacing.
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
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
