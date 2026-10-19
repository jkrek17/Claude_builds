package com.compositioncoach.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CoachDarkColorScheme = darkColorScheme(
    primary = Accent.Action,
    onPrimary = Accent.OnAction,
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
)

/**
 * SceneRight is dark-only by design (Pixel/Apple-camera-style, camera-first UI) — it does not
 * follow the system light/dark setting, so there is a single fixed [darkColorScheme] rather than a
 * light/dark pair.
 */
@Composable
fun CompositionCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CoachDarkColorScheme,
        typography = CoachTypography,
        content = content,
    )
}
