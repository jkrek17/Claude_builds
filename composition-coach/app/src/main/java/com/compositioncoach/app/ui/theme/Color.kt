package com.compositioncoach.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.compositioncoach.app.ui.camera.ScoreTier

// Design tokens for SceneRight's camera-first dark UI (Pixel/Apple-camera restraint): dark neutral
// surfaces, one scrim style, and white/amber/green as the only signal colours over the live preview,
// plus the brand's Apricot for main actions. Every screen composable should reach for these rather
// than an ad-hoc `Color(0xFF...)` literal — see app/README.md's "Design tokens" section and
// docs/BRAND.md "Colour system" (the app colour system approved 19 October 2026).

val Background = Color(0xFF15171B)
val Surface = Color(0xFF24282E)
val OnSurface = Color(0xFFF2F3F4)
val OnSurfaceMuted = Color(0xFFB7BDC4)

/**
 * The accents. [Ready] is "framing is good / shoot now" (the shutter ring, the SHOOT score state, a
 * level horizon). [Warn] is "getting there" (the mid score tier). Both are functional states and keep
 * their validated values. [Action] is the brand's Apricot, used only for main actions
 * (`MaterialTheme.colorScheme.primary`, with Ink text); it never replaces a status colour.
 */
object Accent {
    val Warn = Color(0xFFFFC857)
    val Ready = Color(0xFF34D399)
    val Action = Color(0xFFE9A17E)
    val OnAction = Color(0xFF252A31)
}

/** The single scrim style used behind anything drawn over the live preview: black, 35-45% alpha. */
val Scrim = Color.Black.copy(alpha = 0.4f)

/** A stronger version of [Scrim] for full-screen states that must read over any photo (errors, crash). */
val ScrimStrong = Color.Black.copy(alpha = 0.85f)

val OnScrim = Color.White
val OnScrimMuted = Color.White.copy(alpha = 0.7f)

val ScoreLow = OnScrim
val ScoreGood = Accent.Warn
val ScoreExcellent = Accent.Ready

/** Maps a [ScoreTier] to its display colour, used by the badge and (via tier) previews/tests. */
fun scoreColor(tier: ScoreTier): Color = when (tier) {
    ScoreTier.LOW -> ScoreLow
    ScoreTier.GOOD -> ScoreGood
    ScoreTier.EXCELLENT -> ScoreExcellent
}
