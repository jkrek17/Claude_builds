package com.compositioncoach.app.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.Scrim

/** The score's one type style: 32sp, tabular figures so a changing number never shifts the chip's width. */
private val ScoreNumeralStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum")

/**
 * The optional numeric score (Settings > Show score, default on): a compact chip at the physical-top edge
 * of the preview holding one 32sp tabular numeral, tinted by score tier and nothing else — no pulse, no
 * bump, no colour animation beyond the tier change, per `docs/COACHING_UI.md`.
 *
 * It is deliberately quiet: the readiness arc on the shutter is the primary readout of "how am I doing",
 * and this is the number for people who want one. Hidden entirely when [showScore] is off or before any
 * scene has been classified — the "Point at a subject" chip covers that state instead.
 *
 * TalkBack hears the whole chip as "Composition score 82" (a polite live region, so a meaningful change is
 * announced without the user hunting for it) rather than a bare digit string.
 *
 * Does *not* rotate itself: [CameraScreen] wraps the physical-top stack in one [RotatedChrome].
 */
@Composable
fun ScoreChip(
    score: Int,
    showScore: Boolean,
    hasScene: Boolean,
    isShootReady: Boolean,
    awaitingSubject: Boolean = false,
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    if (!showScore || !hasScene) return

    val stateChangeMs = motionDuration(Motion.STATE_CHANGE_MS, animationsEnabled)
    val animatedScore by animateIntAsState(
        targetValue = score,
        animationSpec = tween(stateChangeMs, easing = LinearOutSlowInEasing),
        label = "score",
    )
    val effectiveShootReady = GuidanceFormatter.effectiveShootReady(isShootReady, awaitingSubject)
    val color by animateColorAsState(
        targetValue = ReadinessArc.color(score, effectiveShootReady),
        animationSpec = tween(stateChangeMs),
        label = "scoreColor",
    )
    val alpha = if (awaitingSubject) GuidanceFormatter.AWAITING_SUBJECT_ARC_ALPHA else 1f

    Text(
        text = animatedScore.toString(),
        color = color.copy(alpha = alpha),
        style = ScoreNumeralStyle,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Scrim)
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clearAndSetSemantics {
                contentDescription = GuidanceFormatter.scoreAnnouncement(score)
                liveRegion = LiveRegionMode.Polite
            },
    )
}

@Preview(name = "Score chip", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ScoreChipPreview() {
    CompositionCoachTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ScoreChip(score = 42, showScore = true, hasScene = true, isShootReady = false, animationsEnabled = false)
            ScoreChip(score = 78, showScore = true, hasScene = true, isShootReady = false, animationsEnabled = false)
            ScoreChip(score = 94, showScore = true, hasScene = true, isShootReady = true, animationsEnabled = false)
            ScoreChip(
                score = 78,
                showScore = true,
                hasScene = true,
                isShootReady = false,
                awaitingSubject = true,
                animationsEnabled = false,
            )
        }
    }
}
