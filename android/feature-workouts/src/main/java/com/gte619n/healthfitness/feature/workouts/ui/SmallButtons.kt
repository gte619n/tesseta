package com.gte619n.healthfitness.feature.workouts.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.gte619n.healthfitness.ui.components.ConfirmDialog
import com.gte619n.healthfitness.ui.theme.Hf

/** "Set as default" / "Default gym" toggle button. */
@Composable
fun SetDefaultButton(
    isDefault: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, enabled = !isDefault, modifier = modifier) {
        Icon(
            if (isDefault) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = null,
        )
        Text(if (isDefault) "  Default gym" else "  Set as default")
    }
}

/** Delete button wrapping the shared [ConfirmDialog]. */
@Composable
fun DeleteLocationButton(
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfirm by remember { mutableStateOf(false) }
    TextButton(onClick = { showConfirm = true }, modifier = modifier) {
        Text("Delete gym", color = Hf.colors.alert)
    }
    if (showConfirm) {
        ConfirmDialog(
            title = "Delete gym",
            message = "This gym will be removed. You can recreate it later.",
            confirmLabel = "Delete",
            destructive = true,
            onConfirm = {
                showConfirm = false
                onConfirm()
            },
            onDismiss = { showConfirm = false },
        )
    }
}
