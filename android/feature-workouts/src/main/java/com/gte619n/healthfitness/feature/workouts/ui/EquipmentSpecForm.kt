package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.workouts.SpecSchemaTag
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Per-category spec editor working on a free-form `Map<String, Any?>` so it
 * serves both the override sheet (partial overrides) and submit-new (full
 * spec, converted to a typed [EquipmentSpec] at submit time). `when(schema)`
 * dispatches to six subforms mirroring web's `EquipmentSpecsForm`.
 */
@Composable
fun EquipmentSpecForm(
    schema: SpecSchemaTag,
    specs: Map<String, Any?>,
    onChange: (Map<String, Any?>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (schema) {
            SpecSchemaTag.SELECTORIZED -> SelectorizedSpecForm(specs, onChange)
            SpecSchemaTag.PLATE_LOADED -> PlateLoadedSpecForm(specs, onChange)
            SpecSchemaTag.BODYWEIGHT -> BodyweightSpecForm()
            SpecSchemaTag.CABLE -> CableSpecForm(specs, onChange)
            SpecSchemaTag.CARDIO -> CardioSpecForm(specs, onChange)
            SpecSchemaTag.WEIGHT_SET -> WeightSetSpecForm(specs, onChange)
        }
    }
}

private fun Map<String, Any?>.num(key: String): String =
    (this[key] as? Number)?.let { if (it.toDouble() % 1.0 == 0.0) it.toLong().toString() else it.toString() }
        ?: (this[key] as? String).orEmpty()

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
private fun SelectorizedSpecForm(specs: Map<String, Any?>, onChange: (Map<String, Any?>) -> Unit) {
    NumberField("Min weight", specs.num("minWeight"), { onChange(specs + ("minWeight" to it.toDoubleOrNull())) }, Modifier.fillMaxWidth())
    NumberField("Max weight", specs.num("maxWeight"), { onChange(specs + ("maxWeight" to it.toDoubleOrNull())) }, Modifier.fillMaxWidth())
    NumberField("Increment", specs.num("increment"), { onChange(specs + ("increment" to it.toDoubleOrNull())) }, Modifier.fillMaxWidth())
}

@Composable
private fun PlateLoadedSpecForm(specs: Map<String, Any?>, onChange: (Map<String, Any?>) -> Unit) {
    NumberField("Bar weight", specs.num("barWeight"), { onChange(specs + ("barWeight" to it.toDoubleOrNull())) }, Modifier.fillMaxWidth())
    DoubleChipRow(
        label = "Available plates",
        values = specs.doubleList("availablePlates"),
        onChange = { onChange(specs + ("availablePlates" to it)) },
    )
}

@Composable
private fun BodyweightSpecForm() {
    Text("No additional specifications required.", style = Hf.type.bodyMd, color = Hf.colors.muted)
}

@Composable
private fun CableSpecForm(specs: Map<String, Any?>, onChange: (Map<String, Any?>) -> Unit) {
    NumberField("Weight stack", specs.num("weightStack"), { onChange(specs + ("weightStack" to it.toDoubleOrNull())) }, Modifier.fillMaxWidth())
    NumberField("Number of stations", specs.num("numStations"), { onChange(specs + ("numStations" to it.toIntOrNull())) }, Modifier.fillMaxWidth())
}

@Composable
private fun CardioSpecForm(specs: Map<String, Any?>, onChange: (Map<String, Any?>) -> Unit) {
    NumberField("Resistance levels", specs.num("resistanceLevels"), { onChange(specs + ("resistanceLevels" to it.toIntOrNull())) }, Modifier.fillMaxWidth())
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Has incline", style = Hf.type.bodyMd, color = Hf.colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = specs["hasIncline"] as? Boolean ?: false,
            onCheckedChange = { onChange(specs + ("hasIncline" to it)) },
        )
    }
}

@Composable
private fun WeightSetSpecForm(specs: Map<String, Any?>, onChange: (Map<String, Any?>) -> Unit) {
    Text("Provide a min/max/increment range OR explicit weights.", style = Hf.type.bodySm, color = Hf.colors.muted)
    NumberField("Min weight", specs.num("minWeight"), { onChange(specs + ("minWeight" to it.toDoubleOrNull())) }, Modifier.fillMaxWidth())
    NumberField("Max weight", specs.num("maxWeight"), { onChange(specs + ("maxWeight" to it.toDoubleOrNull())) }, Modifier.fillMaxWidth())
    NumberField("Increment", specs.num("increment"), { onChange(specs + ("increment" to it.toDoubleOrNull())) }, Modifier.fillMaxWidth())
    DoubleChipRow(
        label = "Explicit weights",
        values = specs.doubleList("weights"),
        onChange = { onChange(specs + ("weights" to it)) },
    )
}

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any?>.doubleList(key: String): List<Double> =
    (this[key] as? List<*>)?.mapNotNull { (it as? Number)?.toDouble() } ?: emptyList()

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DoubleChipRow(
    label: String,
    values: List<Double>,
    onChange: (List<Double>) -> Unit,
) {
    var entry by remember { mutableStateOf("") }
    Text(label, style = Hf.type.capsSm, color = Hf.colors.muted)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        values.forEach { v ->
            InputChip(
                selected = false,
                onClick = { onChange(values - v) },
                label = { Text(if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = entry,
            onValueChange = { entry = it },
            label = { Text("Add value") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = {
            entry.toDoubleOrNull()?.let { onChange(values + it) }
            entry = ""
        }) { Text("Add") }
    }
}
