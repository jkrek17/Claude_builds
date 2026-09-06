package com.compositioncoach.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The default Material3 type scale reads fine over a camera preview at the sizes we use; no custom font. */
val CoachTypography = Typography()

/**
 * The single type scale used across the camera screens, per the design pass:
 *  - the score badge's numerals use [ScoreNumeral]/[ScoreNumeralReady] (display-size, tabular figures —
 *    `"tnum"` — so the badge never jitters in width as digits change);
 *  - instruction headlines use `MaterialTheme.typography.titleMedium` directly;
 *  - secondary/reason lines use `MaterialTheme.typography.bodySmall` at [OnScrimMuted] directly.
 * No other custom text styles exist outside of these — see [GuidanceBanner]/[ScoreBadge] for usage.
 */
val ScoreNumeral = SpanStyle(fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")
val ScoreNumeralReady = SpanStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

/** The one motion vocabulary used everywhere: state changes ease out over 180-250ms, presses take 120ms. */
object Motion {
    const val STATE_CHANGE_MS = 220
    const val PRESS_MS = 120
    const val CROSSFADE_MS = 200
}
