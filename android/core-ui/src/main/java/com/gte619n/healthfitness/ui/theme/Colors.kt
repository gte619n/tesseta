package com.gte619n.healthfitness.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class HfColors(
    val canvas: Color = Color(0xFFF0EBE0),
    val canvasMuted: Color = Color(0xFFEBE4D0),
    val canvasSunken: Color = Color(0xFFF0EBE0),
    val surface: Color = Color(0xFFFFFFFF),

    val borderSubtle: Color = Color(0xFFEFE7D2),
    val borderDefault: Color = Color(0xFFE6DFCF),
    val borderStrong: Color = Color(0xFFDDD3BB),

    val textPrimary: Color = Color(0xFF1F2419),
    val textSecondary: Color = Color(0xFF6B6856),
    val textTertiary: Color = Color(0xFF8A8770),
    val textQuaternary: Color = Color(0xFFB5B09C),
    val textInverse: Color = Color(0xFFF0EBE0),

    val accent: Color = Color(0xFF5C7A2E),
    val accentDim: Color = Color(0xFF3D5A1E),
    val accentBg: Color = Color(0xFFE8EBD8),

    val good: Color = Color(0xFF5C7A2E),
    val goodBg: Color = Color(0xFFE8EBD8),
    val goodAlt: Color = Color(0xFF8A9F5C),
    val warn: Color = Color(0xFFA06A1F),
    val warnBg: Color = Color(0xFFFBE9DA),
    val warnDim: Color = Color(0xFF7C3F0F),
    val alert: Color = Color(0xFFA8473A),
    val alertBg: Color = Color(0xFFF7E1DC),
    val neutral: Color = Color(0xFF3B6B8E),
    val muted: Color = Color(0xFFB5B09C),
)

val LocalHfColors = compositionLocalOf { HfColors() }

object Hf {
    val colors: HfColors
        @Composable
        @ReadOnlyComposable
        get() = LocalHfColors.current
}

@Composable
fun ProvideHfColors(colors: HfColors, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalHfColors provides colors, content = content)
}
