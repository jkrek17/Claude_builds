package com.compositioncoach.app.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.app.ui.theme.scoreColor
import kotlin.math.abs

/** Bold text at [size] with tabular figures ("tnum") so a changing number never shifts the badge's width. */
private fun tabularNumeralStyle(size: TextUnit, weight: FontWeight) =
    TextStyle(fontSize = size, fontWeight = weight, fontFeatureSettings = "tnum")

/** A large change in score (frame-to-frame reframe, not tier-boundary noise) earns a subtle scale-in. */
private const val LARGE_CHANGE_THRESHOLD = 15
private const val BUMP_SCALE = 1.1f

/**
 * The always-visible score readout: a large animated number with tabular figures (so digit changes never
 * shift the badge's width), colour-coded by tier only (no flicker — [scoreColor] animates smoothly between
 * the three tier colours), with a distinct shoot-ready state: "SHOOT" in [Accent.Ready] as the headline,
 * the actual number smaller beneath it. Hidden entirely when [showScore] is off; shows a small
 * "Looking for a subject…" hint instead of a number until [hasScene] is true.
 *
 * While [awaitingSubject] is true the engine's declared shooting mode needs a subject that isn't in frame
 * yet (see [com.compositioncoach.composition.model.SmoothedComposition.awaitingSubject]): [score] is only the
 * last meaningful value the engine is holding, so it renders dimmed with no tier colour and shoot-ready
 * styling never shows — [GuidanceBanner] carries the actual "find your subject" instruction instead.
 *
 * [deviceRotationDegrees] rotates the whole badge in place, Pixel-style, so it stays upright to the person
 * holding the phone (see [rememberControlCounterRotation]) — its position on screen never moves.
 */
@Composable
fun ScoreBadge(
    score: Int,
    isShootReady: Boolean,
    hasScene: Boolean,
    showScore: Boolean,
    awaitingSubject: Boolean = false,
    deviceRotationDegrees: Int = 0,
    modifier: Modifier = Modifier,
) {
    if (!showScore) return
    val controlRotation = rememberControlCounterRotation(deviceRotationDegrees)

    val animatedScore by animateIntAsState(
        targetValue = score,
        animationSpec = tween(Motion.STATE_CHANGE_MS, easing = LinearOutSlowInEasing),
        label = "score",
    )
    val effectiveShootReady = GuidanceFormatter.effectiveShootReady(isShootReady, awaitingSubject)
    val tier = GuidanceFormatter.scoreTier(score)
    val targetColor = when {
        awaitingSubject -> OnScrim
        effectiveShootReady -> Accent.Ready
        else -> scoreColor(tier)
    }
    val color by animateColorAsState(targetColor, animationSpec = tween(Motion.STATE_CHANGE_MS), label = "scoreColor")
    val alpha = GuidanceFormatter.badgeAlpha(awaitingSubject)

    // A subtle one-shot scale-in whenever the score jumps by more than a re-frame's worth of noise —
    // reframing feels acknowledged without the badge pulsing on every small fluctuation. Ease-out only,
    // no overshoot/bounce, per the motion vocabulary.
    val scaleAnim = remember { Animatable(1f) }
    var lastScore by remember { mutableIntStateOf(score) }
    val currentScore by rememberUpdatedState(score)
    LaunchedEffect(score) {
        val jumped = abs(currentScore - lastScore) >= LARGE_CHANGE_THRESHOLD
        lastScore = currentScore
        if (jumped && hasScene) {
            scaleAnim.snapTo(BUMP_SCALE)
            scaleAnim.animateTo(1f, animationSpec = tween(Motion.STATE_CHANGE_MS, easing = LinearOutSlowInEasing))
        }
    }
    val bumpScale = scaleAnim.value

    // A screen reader should hear one clear sentence for the whole badge ("Composition score 82", or
    // "Composition score 94, ready to shoot") rather than the individual number/subtitle Text nodes
    // read separately — clearAndSetSemantics replaces the merged children's own descriptions with this.
    val accessibilityLabel = when {
        !hasScene -> GuidanceFormatter.NO_SUBJECT_TEXT
        effectiveShootReady -> "Composition score $score, ready to shoot"
        else -> "Composition score $score"
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .rotate(controlRotation)
            .clip(RoundedCornerShape(20.dp))
            .background(Scrim)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .scale(bumpScale)
            .clearAndSetSemantics { contentDescription = accessibilityLabel },
    ) {
        if (!hasScene) {
            Text(
                text = GuidanceFormatter.NO_SUBJECT_TEXT,
                color = OnScrimMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        if (effectiveShootReady) {
            Text(text = "SHOOT", color = color, style = tabularNumeralStyle(30.sp, FontWeight.Bold))
            Text(
                text = animatedScore.toString(),
                color = color.copy(alpha = 0.85f),
                style = tabularNumeralStyle(16.sp, FontWeight.SemiBold),
            )
        } else {
            Text(
                text = animatedScore.toString(),
                color = color.copy(alpha = alpha),
                style = tabularNumeralStyle(44.sp, FontWeight.Bold),
            )
        }
    }
}

@Preview(name = "Score states", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun ScoreBadgePreview() {
    CompositionCoachTheme {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScoreBadge(score = 42, isShootReady = false, hasScene = true, showScore = true)
            ScoreBadge(score = 78, isShootReady = false, hasScene = true, showScore = true)
            ScoreBadge(score = 94, isShootReady = true, hasScene = true, showScore = true)
            ScoreBadge(score = 0, isShootReady = false, hasScene = false, showScore = true)
            ScoreBadge(score = 78, isShootReady = true, hasScene = true, showScore = true, awaitingSubject = true)
        }
    }
}
