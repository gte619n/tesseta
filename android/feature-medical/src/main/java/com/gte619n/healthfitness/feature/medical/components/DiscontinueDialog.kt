package com.gte619n.healthfitness.feature.medical.components

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
import androidx.compose.foundation.text.BasicTextField
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
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.DiscontinueReasonLabels
import com.gte619n.healthfitness.ui.components.CapsLabel
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type
import java.time.LocalDate

/**
 * Discontinue dialog with a reason picker, a notes field and an explicit end
 * date ([PR#8]). Mirrors web's discontinue flow; richer than the generic
 * ConfirmDialog so it gets its own composable.
 */
@Composable
fun DiscontinueDialog(
    onConfirm: (reason: DiscontinueReason, notes: String?, endDate: LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf(DiscontinueReason.COMPLETED) }
    var notes by remember { mutableStateOf("") }
    // End date defaults to today; editable via the text field (ISO yyyy-MM-dd).
    var endDateText by remember { mutableStateOf(LocalDate.now().toString()) }

    val parsedEndDate = runCatching { LocalDate.parse(endDateText.trim()) }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discontinue medication", style = Hf.type.headingMd, color = Hf.colors.textPrimary) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                CapsLabel("Reason", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    DiscontinueReason.entries.forEach { entry ->
                        val selected = entry == reason
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) Hf.colors.accentBg else Hf.colors.surface,
                                    RoundedCornerShape(7.dp),
                                )
                                .border(
                                    0.5.dp,
                                    if (selected) Hf.colors.accent else Hf.colors.borderDefault,
                                    RoundedCornerShape(7.dp),
                                )
                                .clickable { reason = entry }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                DiscontinueReasonLabels.label(entry),
                                style = Hf.type.bodySm,
                                color = if (selected) Hf.colors.accentDim else Hf.colors.textSecondary,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                CapsLabel("End date", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogTextField(value = endDateText, onValueChange = { endDateText = it }, placeholder = "yyyy-MM-dd")

                Spacer(Modifier.height(12.dp))
                CapsLabel("Notes", color = Hf.colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                DialogTextField(value = notes, onValueChange = { notes = it }, placeholder = "Optional")
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(reason, notes.ifBlank { null }, parsedEndDate ?: LocalDate.now())
                },
                enabled = parsedEndDate != null,
            ) {
                Text("Discontinue", color = Hf.colors.alert)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Hf.colors.textSecondary)
            }
        },
    )
}

@Composable
private fun DialogTextField(value: String, onValueChange: (String) -> Unit, placeholder: String) {
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
