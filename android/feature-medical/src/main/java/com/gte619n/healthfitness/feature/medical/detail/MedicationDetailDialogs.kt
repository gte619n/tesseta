package com.gte619n.healthfitness.feature.medical.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.FrequencyConfig
import com.gte619n.healthfitness.feature.medical.components.FrequencySelector
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalDate

/**
 * Combined dose + schedule editor ([PR#8]). Dose and schedule are still two
 * distinct backend operations (a dated dose change vs. an immediate schedule
 * update), so [onConfirm] reports each independently: [dose]/[date]/[notes] are
 * non-null only when the dose actually changed, and [frequency] is non-null only
 * when the schedule changed. The caller dispatches whichever parts are present.
 */
@Composable
internal fun EditDoseScheduleDialog(
    currentDose: Double,
    currentUnit: String,
    initialFrequency: FrequencyConfig,
    onConfirm: (dose: Double?, unit: String?, date: LocalDate?, notes: String?, frequency: FrequencyConfig?) -> Unit,
    onDismiss: () -> Unit,
) {
    // Prefill the dose field with the current value so an untouched save is a no-op.
    var dose by remember { mutableStateOf(formatDose(currentDose)) }
    var unit by remember { mutableStateOf(currentUnit) }
    var dateText by remember { mutableStateOf(LocalDate.now().toString()) }
    var notes by remember { mutableStateOf("") }
    var frequency by remember { mutableStateOf(initialFrequency) }

    val parsedDose = dose.toDoubleOrNull()
    val parsedDate = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    val doseChanged = parsedDose != null && (parsedDose != currentDose || unit.trim() != currentUnit)
    val freqChanged = frequency != initialFrequency
    // A blank/invalid dose is only a problem if the user is trying to change it.
    val canSave = parsedDose != null && (!doseChanged || parsedDate != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit dose & schedule", style = Hf.type.headingMd, color = Hf.colors.textPrimary) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                CapsLabel("Dose", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogField(value = dose, onValueChange = { dose = it }, placeholder = "e.g. 250")
                Spacer(Modifier.height(10.dp))
                CapsLabel("Unit", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogField(value = unit, onValueChange = { unit = it }, placeholder = "mg")
                // Effective date + notes only matter when the dose actually changes;
                // they build the dated dosing-history entry.
                if (doseChanged) {
                    Spacer(Modifier.height(10.dp))
                    CapsLabel("Effective date", color = Hf.colors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    DialogField(value = dateText, onValueChange = { dateText = it }, placeholder = "yyyy-MM-dd")
                    Spacer(Modifier.height(10.dp))
                    CapsLabel("Change notes", color = Hf.colors.textSecondary)
                    Spacer(Modifier.height(6.dp))
                    DialogField(value = notes, onValueChange = { notes = it }, placeholder = "Optional")
                }

                Spacer(Modifier.height(16.dp))
                // Reuses the add-flow selector; for weekly meds this exposes the
                // day-of-week chips so a dose can be pinned to e.g. Monday.
                FrequencySelector(config = frequency, onChange = { frequency = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        if (doseChanged) parsedDose else null,
                        unit.ifBlank { null },
                        if (doseChanged) parsedDate else null,
                        notes.ifBlank { null },
                        if (freqChanged) frequency else null,
                    )
                },
                enabled = canSave,
            ) {
                Text("Save", color = Hf.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Hf.colors.textSecondary) }
        },
    )
}

/** Render a dose without a trailing ".0" so whole numbers stay tidy in the field. */
private fun formatDose(dose: Double): String =
    if (dose % 1.0 == 0.0) dose.toLong().toString() else dose.toString()

/** Simple ISO-date entry dialog. */
@Composable
internal fun DatePickerDialog(
    title: String,
    initial: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var dateText by remember { mutableStateOf(initial.toString()) }
    val parsed = runCatching { LocalDate.parse(dateText.trim()) }.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Hf.type.headingMd, color = Hf.colors.textPrimary) },
        text = {
            Column {
                CapsLabel("Date", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogField(value = dateText, onValueChange = { dateText = it }, placeholder = "yyyy-MM-dd")
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(parsed!!) }, enabled = parsed != null) {
                Text("Save", color = Hf.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Hf.colors.textSecondary) }
        },
    )
}

@Composable
internal fun DialogField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Hf.colors.surface, RoundedCornerShape(8.dp))
            .border(0.5.dp, Hf.colors.borderDefault, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = Hf.type.bodyMd, color = Hf.colors.textQuaternary)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = Hf.colors.textPrimary),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Hf.colors.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
