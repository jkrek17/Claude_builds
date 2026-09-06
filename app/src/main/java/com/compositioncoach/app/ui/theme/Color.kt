package com.compositioncoach.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.compositioncoach.app.ui.camera.ScoreTier

// Design tokens for Composition Coach's camera-first dark UI (Pixel/Apple-camera restraint): near-black
// surfaces, one scrim style, and white/amber/green as the *only* accents anywhere on screen. Every screen
// composable should reach for these rather than an ad-hoc `Color(0xFF...)` literal — see app/README.md's
// "Design tokens" section for the rationale.

val Background = Color(0xFF000000)
val Surface = Color(0xFF121212)
val OnSurface = Color(0xFFF2F2F2)

/**
 * The only two non-neutral accents in the app. [Ready] is "framing is good / shoot now" (the shutter
 * ring, the SHOOT score state, a level horizon). [Warn] is "getting there" (the mid score tier, the app's
 * one warm brand accent used for `MaterialTheme.colorScheme.primary`).
 */
object Accent {
    val Warn = Color(0xFFFFC857)
    val Ready = Color(0xFF34D399)
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
