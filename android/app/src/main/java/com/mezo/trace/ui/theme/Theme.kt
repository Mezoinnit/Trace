package com.mezo.trace.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Trace visual language: dark-first, deep charcoal surfaces with a single
 * violet accent. Calm, dense-neutral, modern. Light variant derives from the
 * same palette.
 */

// ---- Brand palette ----
val Violet = Color(0xFF7C6BF2)
val VioletDim = Color(0xFF5B4BC4)
val VioletContainer = Color(0xFF352F63)
val Ink = Color(0xFF0E0F14) // near-black canvas
val Surface1 = Color(0xFF16171F)
val Surface2 = Color(0xFF1E1F29)
val Surface3 = Color(0xFF2A2B37)
val TextPrimary = Color(0xFFE9E9F2)
val TextSecondary = Color(0xFF9BA0B0)
val Teal = Color(0xFF4FD8C4)
val Amber = Color(0xFFF5B84C)
val Red = Color(0xFFEF5B6E)

// ---- Semantic significance bands (badges / card accents) ----
val HighSig = Color(0xFFEF5B6E)
val MediumSig = Color(0xFFF5B84C)
val LowSig = Color(0xFF4FD8C4)
val InfoSig = Color(0xFF7C6BF2)

private val DarkColors = darkColorScheme(
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = VioletContainer,
    onPrimaryContainer = Color(0xFFE6E1FF),
    secondary = Teal,
    onSecondary = Color(0xFF00312B),
    secondaryContainer = Color(0xFF123F39),
    onSecondaryContainer = Color(0xFFC8F5EC),
    tertiary = Amber,
    onTertiary = Color(0xFF3D2E00),
    tertiaryContainer = Color(0xFF4A3A12),
    onTertiaryContainer = Color(0xFFFFE1A1),
    error = Red,
    onError = Color.White,
    errorContainer = Color(0xFF4A1E25),
    onErrorContainer = Color(0xFFFFD9DD),
    background = Ink,
    onBackground = TextPrimary,
    surface = Surface1,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = Surface2,
    surfaceContainerHigh = Surface3,
    surfaceContainerHighest = Surface3,
    outline = Color(0xFF4A4C5C),
    outlineVariant = Color(0xFF333542),
)

private val LightColors = lightColorScheme(
    primary = VioletDim,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E1FF),
    onPrimaryContainer = Color(0xFF241A5E),
    secondary = Color(0xFF177563),
    onSecondary = Color.White,
    tertiary = Color(0xFF8A5A00),
    error = Color(0xFFBA1A1A),
    background = Color(0xFFF7F7FB),
    onBackground = Color(0xFF1A1B22),
    surface = Color.White,
    onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFE8E9F1),
    onSurfaceVariant = Color(0xFF5A5D6C),
    outline = Color(0xFFB9BCC9),
)

object TraceDimens {
    val ScreenPad = 20.dp
    val CardPad = 16.dp
    val CardRadius = 16.dp
    val ButtonRadius = 28.dp
    val FieldRadius = 14.dp
    val ButtonHeight = 52.dp
}

val TraceTypography = androidx.compose.material3.Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.4.sp,
    ),
)

/** Semantic accent for significance bands; falls back to primary. */
fun significanceColor(value: String?): Color = when (value?.uppercase()) {
    "HIGH", "CRITICAL" -> HighSig
    "MEDIUM" -> MediumSig
    "LOW" -> LowSig
    "INFO" -> InfoSig
    else -> Violet
}

@Composable
fun TraceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = TraceTypography,
        content = content,
    )
}
