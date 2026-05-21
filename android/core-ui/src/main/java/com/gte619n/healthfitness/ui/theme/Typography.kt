package com.gte619n.healthfitness.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.gte619n.healthfitness.ui.R

private val googleProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs,
)

private fun font(name: String, weight: FontWeight) = Font(
    googleFont = GoogleFont(name),
    fontProvider = googleProvider,
    weight = weight,
    style = FontStyle.Normal,
)

val HfSans: FontFamily = FontFamily(
    font("Instrument Sans", FontWeight.Normal),
    font("Instrument Sans", FontWeight.Medium),
)

val HfMono: FontFamily = FontFamily(
    font("JetBrains Mono", FontWeight.Normal),
    font("JetBrains Mono", FontWeight.Medium),
)

@Immutable
data class HfTypography(
    // Mono numerics
    val displayXl: TextStyle = TextStyle(
        fontFamily = HfMono,
        fontWeight = FontWeight.Medium,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.03).sp,
    ),
    val displayLg: TextStyle = TextStyle(
        fontFamily = HfMono,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.02).sp,
    ),
    val displayMd: TextStyle = TextStyle(
        fontFamily = HfMono,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.02).sp,
    ),
    val displaySm: TextStyle = TextStyle(
        fontFamily = HfMono,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.01).sp,
    ),
    // Sans headings
    val headingLg: TextStyle = TextStyle(
        fontFamily = HfSans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.015).sp,
    ),
    val headingMd: TextStyle = TextStyle(
        fontFamily = HfSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = (-0.01).sp,
    ),
    val headingSm: TextStyle = TextStyle(
        fontFamily = HfSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = (-0.01).sp,
    ),
    // Sans body
    val bodyLg: TextStyle = TextStyle(
        fontFamily = HfSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 22.sp,
    ),
    val bodyMd: TextStyle = TextStyle(
        fontFamily = HfSans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    val bodySm: TextStyle = TextStyle(
        fontFamily = HfSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // Mono inline
    val monoLg: TextStyle = TextStyle(
        fontFamily = HfMono,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        lineHeight = 22.sp,
    ),
    val monoMd: TextStyle = TextStyle(
        fontFamily = HfMono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    val monoSm: TextStyle = TextStyle(
        fontFamily = HfMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
    // Mono caps labels (tracking +0.08em)
    val capsMd: TextStyle = TextStyle(
        fontFamily = HfMono,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.8.sp,
    ),
    val capsSm: TextStyle = TextStyle(
        fontFamily = HfMono,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.72.sp,
    ),
)

val LocalHfTypography = compositionLocalOf { HfTypography() }

val Hf.type: HfTypography
    @Composable
    @ReadOnlyComposable
    get() = LocalHfTypography.current
