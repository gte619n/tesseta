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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.domain.medications.DiscontinueReason
import com.gte619n.healthfitness.domain.medications.DiscontinueReasonLabels
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Discontinue confirmation dialog with a reason picker + free-form
 * notes. Submission requires a reason; notes are optional.
 */
@Composable
fun DiscontinueDialog(
    onDismiss: () -> Unit,
    onConfirm: (reason: DiscontinueReason, notes: String?) -> Unit,
    initialReason: DiscontinueReason = DiscontinueReason.COMPLETED,
) {
    var reason by remember { mutableStateOf(initialReason) }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Discontinue medication", style = Hf.type.headingMd) },
        text = {
            Column {
                Text("Why are you stopping this medication?", style = Hf.type.bodySm, color = Hf.colors.textSecondary)
                Spacer(Modifier.height(10.dp))
                DiscontinueReason.values().forEach { value ->
                    ReasonRow(
                        label = DiscontinueReasonLabels.label(value),
                        selected = value == reason,
                        onClick = { reason = value },
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason, notes.ifBlank { null }) }) {
                Text("Discontinue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun ReasonRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) Hf.colors.accentBg else Hf.colors.surface)
            .border(
                0.5.dp,
                if (selected) Hf.colors.accent else Hf.colors.borderDefault,
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) Hf.colors.accent else Hf.colors.canvasMuted)
                .height(8.dp)
                .padding(end = 8.dp),
        )
        Spacer(Modifier.padding(start = 8.dp))
        Text(
            text = label,
            style = Hf.type.bodyMd,
            color = if (selected) Hf.colors.accentDim else Hf.colors.textPrimary,
        )
    }
}
