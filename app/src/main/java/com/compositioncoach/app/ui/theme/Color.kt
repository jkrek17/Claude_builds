package com.compositioncoach.app.ui.theme

import androidx.compose.ui.graphics.Color
import com.compositioncoach.app.ui.camera.ScoreTier

// A restrained, camera-first dark palette: near-black surfaces, a single warm accent, and the three
// score bands used for the badge. Nothing here competes with the photo underneath.
val Background = Color(0xFF000000)
val Surface = Color(0xFF121212)
val OnSurface = Color(0xFFF2F2F2)
val Accent = Color(0xFFFFC857)

val ScoreLow = Color(0xFFF5F5F5)
val ScoreGood = Color(0xFFFFC857)
val ScoreExcellent = Color(0xFF34D399)

/** Maps a [ScoreTier] to its display colour, used by the badge and (via tier) previews/tests. */
fun scoreColor(tier: ScoreTier): Color = when (tier) {
    ScoreTier.LOW -> ScoreLow
    ScoreTier.GOOD -> ScoreGood
    ScoreTier.EXCELLENT -> ScoreExcellent
}
