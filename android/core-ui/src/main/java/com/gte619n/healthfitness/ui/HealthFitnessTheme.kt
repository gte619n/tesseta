package com.gte619n.healthfitness.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.gte619n.healthfitness.ui.theme.HfColors
import com.gte619n.healthfitness.ui.theme.HfTypography
import com.gte619n.healthfitness.ui.theme.LocalHfColors
import com.gte619n.healthfitness.ui.theme.LocalHfTypography

/**
 * Health & Fitness theme. Provides the project's design-token color and type
 * stacks via composition locals. Falls back to Material 3's color scheme for
 * any Compose primitives that bypass our tokens (Material 3 widgets, system
 * dialogs).
 */
@Composable
fun HealthFitnessTheme(content: @Composable () -> Unit) {
    val colors = HfColors()
    val typography = HfTypography()

    CompositionLocalProvider(
        LocalHfColors provides colors,
        LocalHfTypography provides typography,
    ) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = colors.accent,
                onPrimary = colors.textInverse,
                background = colors.canvas,
                onBackground = colors.textPrimary,
                surface = colors.surface,
                onSurface = colors.textPrimary,
            ),
            content = content,
        )
    }
}
