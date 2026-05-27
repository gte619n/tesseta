package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.EquipmentSpec
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Dispatches to the right per-category form. Mirrors the web's
 * `EquipmentSpecsForm.tsx`.
 *
 * The spec passed in becomes the seed for the per-category form fields;
 * when [onChange] fires we hand back the same sealed-class type so the
 * caller can serialise via [com.gte619n.healthfitness.data.workouts.WorkoutsMappers.specsToMap].
 *
 * If the [schema] doesn't match the [current] subtype (e.g., the form
 * was just opened for a different category), the form seeds from a
 * sensible default for that schema.
 */
@Composable
fun EquipmentSpecForm(
    schema: SpecSchemaTag,
    current: EquipmentSpec,
    onChange: (EquipmentSpec) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when (schema) {
            SpecSchemaTag.SELECTORIZED -> SelectorizedForm(
                current as? EquipmentSpec.Selectorized
                    ?: EquipmentSpec.Selectorized(0.0, 0.0, 0.0),
                onChange,
            )
            SpecSchemaTag.PLATE_LOADED -> PlateLoadedForm(
                current as? EquipmentSpec.PlateLoaded
                    ?: EquipmentSpec.PlateLoaded(0.0, emptyList()),
                onChange,
            )
            SpecSchemaTag.BODYWEIGHT -> BodyweightInfo()
            SpecSchemaTag.CABLE -> CableForm(
                current as? EquipmentSpec.Cable ?: EquipmentSpec.Cable(0.0, 1),
                onChange,
            )
            SpecSchemaTag.CARDIO -> CardioForm(
                current as? EquipmentSpec.Cardio ?: EquipmentSpec.Cardio(0, false),
                onChange,
            )
            SpecSchemaTag.WEIGHT_SET -> WeightSetForm(
                current as? EquipmentSpec.WeightSet
                    ?: EquipmentSpec.WeightSet(null, null, null, null),
                onChange,
            )
        }
    }
}

@Composable
private fun SelectorizedForm(
    state: EquipmentSpec.Selectorized,
    onChange: (EquipmentSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NumericField(
            label = "Min weight (lb)",
            value = state.minWeight,
            onChange = { onChange(state.copy(minWeight = it)) },
        )
        NumericField(
            label = "Max weight (lb)",
            value = state.maxWeight,
            onChange = { onChange(state.copy(maxWeight = it)) },
        )
        NumericField(
            label = "Increment (lb)",
            value = state.increment,
            onChange = { onChange(state.copy(increment = it)) },
        )
    }
}

@Composable
private fun PlateLoadedForm(
    state: EquipmentSpec.PlateLoaded,
    onChange: (EquipmentSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NumericField(
            label = "Bar weight (lb)",
            value = state.barWeight,
            onChange = { onChange(state.copy(barWeight = it)) },
        )
        Text(
            "Available plates (lb)",
            style = Hf.type.bodySm,
            color = Hf.colors.textSecondary,
        )
        WeightChipRow(
            weights = state.availablePlates,
            onAdd = { onChange(state.copy(availablePlates = state.availablePlates + it)) },
            onRemove = {
                val next = state.availablePlates.toMutableList().apply { removeAt(it) }
                onChange(state.copy(availablePlates = next))
            },
        )
    }
}

@Composable
private fun BodyweightInfo() {
    Text(
        "No additional specifications required for bodyweight equipment.",
        style = Hf.type.bodySm,
        color = Hf.colors.textTertiary,
    )
}

@Composable
private fun CableForm(
    state: EquipmentSpec.Cable,
    onChange: (EquipmentSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NumericField(
            label = "Weight stack (lb)",
            value = state.weightStack,
            onChange = { onChange(state.copy(weightStack = it)) },
        )
        NumericField(
            label = "Number of stations",
            value = state.numStations.toDouble(),
            onChange = { onChange(state.copy(numStations = it.toInt())) },
            integer = true,
        )
    }
}

@Composable
private fun CardioForm(
    state: EquipmentSpec.Cardio,
    onChange: (EquipmentSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        NumericField(
            label = "Resistance levels",
            value = state.resistanceLevels.toDouble(),
            onChange = { onChange(state.copy(resistanceLevels = it.toInt())) },
            integer = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.hasIncline,
                onCheckedChange = { onChange(state.copy(hasIncline = it)) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Hf.colors.surface,
                    checkedTrackColor = Hf.colors.accent,
                    uncheckedThumbColor = Hf.colors.surface,
                    uncheckedTrackColor = Hf.colors.borderStrong,
                ),
            )
            Spacer(Modifier.width(10.dp))
            Text("Has incline", style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
        }
    }
}

@Composable
private fun WeightSetForm(
    state: EquipmentSpec.WeightSet,
    onChange: (EquipmentSpec) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Either set a min / max / increment range OR list explicit weights.",
            style = Hf.type.bodySm,
            color = Hf.colors.textTertiary,
        )
        OptionalNumericField(
            label = "Min weight",
            value = state.minWeight,
            onChange = { onChange(state.copy(minWeight = it)) },
        )
        OptionalNumericField(
            label = "Max weight",
            value = state.maxWeight,
            onChange = { onChange(state.copy(maxWeight = it)) },
        )
        OptionalNumericField(
            label = "Increment",
            value = state.increment,
            onChange = { onChange(state.copy(increment = it)) },
        )
        Text(
            "Explicit weights",
            style = Hf.type.bodySm,
            color = Hf.colors.textSecondary,
        )
        WeightChipRow(
            weights = state.weights.orEmpty(),
            onAdd = {
                val list = state.weights.orEmpty() + it
                onChange(state.copy(weights = list))
            },
            onRemove = {
                val next = (state.weights.orEmpty()).toMutableList().apply { removeAt(it) }
                onChange(state.copy(weights = next.ifEmpty { null }))
            },
        )
    }
}

// ---- shared sub-composables -------------------------------------------

@Composable
private fun NumericField(
    label: String,
    value: Double,
    onChange: (Double) -> Unit,
    integer: Boolean = false,
) {
    var text by remember(value) {
        mutableStateOf(if (value == 0.0) "" else value.formatNumber(integer))
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            val parsed = raw.toDoubleOrNull()
            if (parsed != null) onChange(parsed)
        },
        label = { Text(label, style = Hf.type.bodySm) },
        textStyle = Hf.type.bodyMd,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OptionalNumericField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
) {
    var text by remember(value) {
        mutableStateOf(value?.formatNumber(false).orEmpty())
    }
    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            text = raw
            onChange(if (raw.isBlank()) null else raw.toDoubleOrNull())
        },
        label = { Text(label, style = Hf.type.bodySm) },
        textStyle = Hf.type.bodyMd,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeightChipRow(
    weights: List<Double>,
    onAdd: (Double) -> Unit,
    onRemove: (Int) -> Unit,
) {
    var newWeight by remember { mutableStateOf("") }
    Column {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            weights.forEachIndexed { index, w ->
                Box(
                    modifier = Modifier
                        .background(Hf.colors.accentBg, RoundedCornerShape(20.dp))
                        .border(0.5.dp, Hf.colors.accent, RoundedCornerShape(20.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            w.formatNumber(false),
                            style = Hf.type.bodySm,
                            color = Hf.colors.accentDim,
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Remove",
                            tint = Hf.colors.accentDim,
                            modifier = Modifier
                                .height(12.dp)
                                .width(12.dp)
                                .clickable { onRemove(index) },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newWeight,
                onValueChange = { newWeight = it },
                placeholder = { Text("Add weight", style = Hf.type.bodySm) },
                textStyle = Hf.type.bodyMd,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(Hf.colors.accent, RoundedCornerShape(6.dp))
                    .clickable {
                        val parsed = newWeight.toDoubleOrNull()
                        if (parsed != null) {
                            onAdd(parsed)
                            newWeight = ""
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = "Add weight", tint = Hf.colors.surface)
            }
        }
    }
}

private fun Double.formatNumber(integer: Boolean): String =
    if (integer || this == toLong().toDouble()) toLong().toString() else toString()
