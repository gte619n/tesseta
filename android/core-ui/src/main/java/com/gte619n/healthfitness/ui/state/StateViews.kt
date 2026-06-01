package com.gte619n.healthfitness.ui.state

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gte619n.healthfitness.ui.HealthFitnessTheme
import com.gte619n.healthfitness.ui.theme.Hf
import com.gte619n.healthfitness.ui.theme.type

// IMPL-AND-00 shared state primitives. Every feature screen dispatches through
// these so loading / empty / error treatment matches across the app.

@Composable
fun LoadingState(modifier: Modifier = Modifier, label: String? = null) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Hf.colors.accent, strokeWidth = 2.dp)
        if (label != null) {
            Spacer(Modifier.height(12.dp))
            Text(label, style = Hf.type.bodyMd, color = Hf.colors.textTertiary)
        }
    }
}

@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Hf.colors.textQuaternary)
            Spacer(Modifier.height(12.dp))
        }
        Text(
            title,
            style = Hf.type.headingMd,
            color = Hf.colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                description,
                style = Hf.type.bodyMd,
                color = Hf.colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            style = Hf.type.bodyMd,
            color = Hf.colors.alert,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) {
                Text("Retry", style = Hf.type.bodyMd, color = Hf.colors.accent)
            }
        }
    }
}

@Preview
@Composable
private fun LoadingPreview() = HealthFitnessTheme { LoadingState(label = "Loading…") }

@Preview
@Composable
private fun EmptyPreview() = HealthFitnessTheme {
    EmptyState(title = "Nothing here yet", description = "Add your first entry to get started.")
}

@Preview
@Composable
private fun ErrorPreview() = HealthFitnessTheme {
    ErrorState(message = "Couldn't load data", onRetry = {})
}
