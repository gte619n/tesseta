package com.gte619n.healthfitness.feature.goals

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

// Shared editor atoms for GoalProposalCard. All styled with core-ui tokens.

@Composable
internal fun EditableField(
    label: String?,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Column {
        if (label != null) FieldLabelInternal(label)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(7.dp))
                .background(Hf.colors.surface, RoundedCornerShape(7.dp))
                .padding(horizontal = 9.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = singleLine,
                textStyle = Hf.type.bodyMd.copy(color = Hf.colors.textPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Hf.colors.accent),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) {
                        Text(placeholder, style = Hf.type.bodyMd, color = Hf.colors.textQuaternary)
                    }
                    inner()
                },
            )
        }
    }
}

@Composable
private fun FieldLabelInternal(text: String) {
    com.gte619n.healthfitness.ui.components.CapsLabel(text, color = Hf.colors.textTertiary, size = 9)
    Spacer(Modifier.height(3.dp))
}

/** A generic single-select dropdown over [options]. */
@Composable
internal fun <T> EnumDropdown(
    selected: T,
    options: List<T>,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(7.dp))
                .background(Hf.colors.surface, RoundedCornerShape(7.dp))
                .clickable { expanded = true }
                .padding(horizontal = 9.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(labelOf(selected), style = Hf.type.bodyMd, color = Hf.colors.textPrimary)
            Icon(
                Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = Hf.colors.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelOf(option), style = Hf.type.bodyMd, color = Hf.colors.textPrimary) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A date field that opens a Material3 date picker; stores ISO "yyyy-MM-dd". */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(value: String?, onChange: (String) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(7.dp))
            .background(Hf.colors.surface, RoundedCornerShape(7.dp))
            .clickable { showPicker = true }
            .padding(horizontal = 9.dp, vertical = 8.dp),
    ) {
        val display = formatCapsDate(value).takeIf { it != "—" } ?: "Pick a date"
        Text(
            display,
            style = Hf.type.monoMd,
            color = if (value.isNullOrBlank()) Hf.colors.textQuaternary else Hf.colors.textPrimary,
        )
    }

    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = isoToEpochMillis(value))
        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(epochMillisToIso(it)) }
                    showPicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}
