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
import androidx.compose.ui.unit.sp
import com.survivor.engine.Tier

private val Light = lightColorScheme(
    primary = Color(0xFF125C43), onPrimary = Color.White,
    primaryContainer = Color(0xFFBFE8D3), onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF4A635A), secondaryContainer = Color(0xFFCDE9DB),
    surface = Color(0xFFFBFDF9), background = Color(0xFFFBFDF9),
    surfaceVariant = Color(0xFFDCE5DD), onSurfaceVariant = Color(0xFF404944),
    error = Color(0xFFBA1A1A),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8CD5B1), onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF0B3D2E), onPrimaryContainer = Color(0xFFBFE8D3),
    secondary = Color(0xFFB1CCC0), secondaryContainer = Color(0xFF334B42),
    surface = Color(0xFF191C1A), background = Color(0xFF191C1A),
    surfaceVariant = Color(0xFF404944), onSurfaceVariant = Color(0xFFBFC9C2),
)

val AppTypography = Typography(
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun SurvivorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = AppTypography, content = content)
}

/** Green / yellow / orange / red for Strong / Acceptable / Risky / Avoid. */
@Composable
fun tierColor(tier: Tier): Color = when (tier) {
    Tier.STRONG -> Color(0xFF2E7D32)
    Tier.ACCEPTABLE -> Color(0xFFC49A00)
    Tier.RISKY -> Color(0xFFEF6C00)
    Tier.AVOID -> Color(0xFFC62828)
}

@Composable
fun tierContainer(tier: Tier): Color {
    val dark = isSystemInDarkTheme()
    return when (tier) {
        Tier.STRONG -> if (dark) Color(0xFF1B4D1F) else Color(0xFFC8E6C9)
        Tier.ACCEPTABLE -> if (dark) Color(0xFF5A4A00) else Color(0xFFFFF59D)
        Tier.RISKY -> if (dark) Color(0xFF6B3A00) else Color(0xFFFFCC80)
        Tier.AVOID -> if (dark) Color(0xFF5E1B1B) else Color(0xFFFFCDD2)
    }
}

/** Darker green for the >82% "premium" schedule-grid cells. */
@Composable
fun premiumContainer(): Color = if (isSystemInDarkTheme()) Color(0xFF0F6B2E) else Color(0xFF81C784)
