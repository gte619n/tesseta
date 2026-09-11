package com.gte619n.healthfitness.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.sp
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
            // Map the Hf type stack onto Material's roles so M3 widgets (button
            // labels, text fields, dialogs, menus) and any unstyled Text render
            // the app fonts instead of falling back to Roboto defaults.
            typography = Typography(
                displayLarge = typography.displayXl,
                displayMedium = typography.displayLg,
                displaySmall = typography.displayMd,
                headlineLarge = typography.headingLg.copy(fontSize = 28.sp, lineHeight = 36.sp),
                headlineMedium = typography.headingLg.copy(fontSize = 24.sp, lineHeight = 32.sp),
                headlineSmall = typography.headingLg,
                titleLarge = typography.headingLg,
                titleMedium = typography.headingMd,
                titleSmall = typography.headingSm,
                bodyLarge = typography.bodyLg,
                bodyMedium = typography.bodyMd,
                bodySmall = typography.bodySm,
                labelLarge = typography.headingMd,
                labelMedium = typography.headingSm,
                labelSmall = typography.headingSm.copy(fontSize = 11.sp, lineHeight = 16.sp),
            ),
            content = content,
        )
    }
}
