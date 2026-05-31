package com.gte619n.healthfitness.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

/**
 * Generic confirm dialog mirroring web's `useConfirm()` (IMPL-AND-00). Used by
 * destructive actions (delete gym / report / scan, discontinue, sign out).
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Confirm",
    dismissLabel: String = "Cancel",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = Hf.type.headingMd, color = Hf.colors.textPrimary) },
        text = { Text(message, style = Hf.type.bodyMd, color = Hf.colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    style = Hf.type.bodyMd,
                    color = if (destructive) Hf.colors.alert else Hf.colors.accent,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, style = Hf.type.bodyMd, color = Hf.colors.textTertiary)
            }
        },
        containerColor = Hf.colors.surface,
    )
}
