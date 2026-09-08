package com.survivor.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.survivor.engine.Tier

/**
 * Deep-green primary, warm neutral surfaces. Both schemes keep AA-level contrast between `on*` text
 * colors and their surfaces; verify any new color pairing against that before adding it.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF0F5C3F), onPrimary = Color.White,
    primaryContainer = Color(0xFFBCEBD1), onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4C6358), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9DB), onSecondaryContainer = Color(0xFF092017),
    tertiary = Color(0xFF3D6373), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC0E8FB), onTertiaryContainer = Color(0xFF001F29),
    background = Color(0xFFFAF8F3), onBackground = Color(0xFF1A1C19),
    surface = Color(0xFFFAF8F3), onSurface = Color(0xFF1A1C19),
    surfaceVariant = Color(0xFFE0E4DA), onSurfaceVariant = Color(0xFF43483F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F2EC),
    surfaceContainer = Color(0xFFEEECE4),
    surfaceContainerHigh = Color(0xFFE8E6DE),
    surfaceContainerHighest = Color(0xFFE2E0D8),
    outline = Color(0xFF73796E), outlineVariant = Color(0xFFC3C8BB),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FD6B0), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0B4D36), onPrimaryContainer = Color(0xFFBCEBD1),
    secondary = Color(0xFFB2CCBE), onSecondary = Color(0xFF1E352B),
    secondaryContainer = Color(0xFF344B41), onSecondaryContainer = Color(0xFFCEE9DB),
    tertiary = Color(0xFFA5CCDE), onTertiary = Color(0xFF063542),
    tertiaryContainer = Color(0xFF244C5B), onTertiaryContainer = Color(0xFFC0E8FB),
    background = Color(0xFF12140F), onBackground = Color(0xFFE2E3DB),
    surface = Color(0xFF12140F), onSurface = Color(0xFFE2E3DB),
    surfaceVariant = Color(0xFF43483F), onSurfaceVariant = Color(0xFFC3C8BB),
    surfaceContainerLowest = Color(0xFF0C0E09),
    surfaceContainerLow = Color(0xFF1A1C17),
    surfaceContainer = Color(0xFF1E211B),
    surfaceContainerHigh = Color(0xFF292C25),
    surfaceContainerHighest = Color(0xFF343730),
    outline = Color(0xFF8D9285), outlineVariant = Color(0xFF43483F),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

/** 4/8/16 dp spacing rhythm used across the app instead of ad-hoc dp literals. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

/** Corner radius for every card in the app. */
val CardRadius = 16.dp

@Composable
fun SurvivorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = AppTypography, content = content)
}

/** Green / yellow / orange / red for Strong / Acceptable / Risky / Avoid, used as accents (chips, small
 *  bars) rather than full-row backgrounds. */
@Composable
fun tierColor(tier: Tier): Color {
    val dark = isSystemInDarkTheme()
    return when (tier) {
        Tier.STRONG -> if (dark) Color(0xFF7FDB9C) else Color(0xFF1E7D3C)
        Tier.ACCEPTABLE -> if (dark) Color(0xFFE6C74A) else Color(0xFF8A6D00)
        Tier.RISKY -> if (dark) Color(0xFFFFB068) else Color(0xFFB25900)
        Tier.AVOID -> if (dark) Color(0xFFFFB0A6) else Color(0xFFB3261E)
    }
}

/** Soft tinted container for a tier chip's background - never used as a full-row fill. */
@Composable
fun tierContainer(tier: Tier): Color {
    val dark = isSystemInDarkTheme()
    return when (tier) {
        Tier.STRONG -> if (dark) Color(0xFF163825) else Color(0xFFDCF3E2)
        Tier.ACCEPTABLE -> if (dark) Color(0xFF3A3212) else Color(0xFFFBF0C7)
        Tier.RISKY -> if (dark) Color(0xFF3E2A11) else Color(0xFFFCE3C7)
        Tier.AVOID -> if (dark) Color(0xFF3D1614) else Color(0xFFFAD9D6)
    }
}

/** A faint, barely-there wash for whole-row/card backgrounds where a tier still needs to register at a glance. */
@Composable
fun tierWash(tier: Tier): Color = tierContainer(tier).copy(alpha = 0.35f)

/** Darker green for the >82% "premium" schedule-grid cells. */
@Composable
fun premiumContainer(): Color = if (isSystemInDarkTheme()) Color(0xFF0F6B2E) else Color(0xFF81C784)
