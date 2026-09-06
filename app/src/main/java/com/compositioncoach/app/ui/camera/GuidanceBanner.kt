package com.compositioncoach.app.ui.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.Severity

private const val MAX_LINES = 3

/** Reserves height for one titleMedium line so a shorter/longer instruction never resizes the banner. */
private val HEADLINE_LINE_HEIGHT = 28.dp

/**
 * The instruction readout below the score: the primary recommendation crossfades as it changes (a vector
 * [DirectionIcon] to its left rather than a text glyph), an optional COACH-mode reason sits under it, and
 * up to two secondary recommendations follow in smaller type. Never wider than 85% of the screen, and
 * never overlaps [ScoreBadge] above it (the two are positioned independently by [CameraScreen]).
 *
 * While [awaitingSubject] is true, [activeRecommendations] holds exactly the single find-subject
 * recommendation (see [com.compositioncoach.composition.model.SmoothedComposition.awaitingSubject]) and this
 * renders it prominently instead: its title as a small line, its instruction as the headline, no directional
 * icon (the recommendation has no direction to point in).
 */
@Composable
fun GuidanceBanner(
    activeRecommendations: List<Recommendation>,
    guidanceLevel: GuidanceLevel,
    awaitingSubject: Boolean = false,
    displayScore: Int = 0,
    isShootReady: Boolean = false,
    hasScene: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // LocalWindowInfo.containerSize (px), not Configuration.screenWidthDp, per the accurate-window-size
    // guidance for Compose (Configuration's dp values round and vary with target SDK inset behaviour).
    val density = LocalDensity.current
    val screenWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val maxWidth = screenWidthDp * 0.85f
    val primary = activeRecommendations.firstOrNull()
    if (primary == null) {
        // No advice: when the framing is decent, say so quietly, so advice clearing reads as success
        // rather than the banner vanishing. Shoot-ready already speaks through the score badge.
        if (hasScene && !isShootReady && GuidanceFormatter.showsHoldFramingHint(displayScore)) {
            Text(
                text = GuidanceFormatter.HOLD_FRAMING_HINT,
                color = OnScrimMuted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = modifier
                    .widthIn(max = maxWidth)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Scrim)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    // Announce "framing looks good, hold steady" once when it appears, same as any other
                    // change in guidance text.
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        return
    }

    if (awaitingSubject) {
        val awaitingDescription =
            "${GuidanceFormatter.awaitingSubjectTitleLine(primary)}. ${GuidanceFormatter.awaitingSubjectHeadline(primary)}"
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = modifier
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(16.dp))
                .background(Scrim)
                .padding(horizontal = 20.dp, vertical = 10.dp)
                // TalkBack should hear the "looking for a subject" guidance as one sentence, and hear it
                // again whenever it changes (e.g. from "Looking for a face" to a different find-subject cue).
                .clearAndSetSemantics {
                    contentDescription = awaitingDescription
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            Text(
                text = GuidanceFormatter.awaitingSubjectTitleLine(primary),
                color = OnScrimMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Text(
                text = GuidanceFormatter.awaitingSubjectHeadline(primary),
                color = OnScrim,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    val reason = GuidanceFormatter.reasonLine(primary, guidanceLevel)
    val budgetForSecondary = (MAX_LINES - 1 - (if (reason != null) 1 else 0)).coerceAtLeast(0)
    val secondary = activeRecommendations.drop(1).take(budgetForSecondary)
    // The full guidance sentence TalkBack announces: primary instruction, then the COACH-mode reason
    // (if shown), then any secondary recommendations — in the same order they're drawn.
    val guidanceDescription = buildList {
        add(GuidanceFormatter.primaryLine(primary))
        reason?.let(::add)
        secondary.forEach { add(GuidanceFormatter.secondaryLine(it)) }
    }.joinToString(". ")

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(16.dp))
            .background(Scrim)
            .padding(horizontal = 20.dp, vertical = 10.dp)
            // liveRegion = Polite is what makes TalkBack announce new advice as it changes, unprompted,
            // instead of only when the user explicitly navigates focus to this banner.
            .clearAndSetSemantics {
                contentDescription = guidanceDescription
                liveRegion = LiveRegionMode.Polite
            },
    ) {
        AnimatedContent(
            targetState = primary,
            transitionSpec = { fadeIn(tween(Motion.CROSSFADE_MS)) togetherWith fadeOut(tween(Motion.CROSSFADE_MS)) },
            label = "primaryInstruction",
            modifier = Modifier.heightIn(min = HEADLINE_LINE_HEIGHT),
        ) { rec ->
            // Fixed to one line (ellipsized, not wrapped) so a longer instruction never grows the
            // banner's height and shifts the shutter row/onboarding card beneath it — the whole point of
            // reserving [HEADLINE_LINE_HEIGHT] above.
            Row(verticalAlignment = Alignment.CenterVertically) {
                DirectionIcon(direction = rec.direction, tint = OnScrim, modifier = Modifier.padding(end = 4.dp))
                Text(
                    text = rec.instruction,
                    color = OnScrim,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        reason?.let {
            Text(text = it, color = OnScrimMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        }
        secondary.forEach { rec ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                DirectionIcon(direction = rec.direction, tint = OnScrimMuted, size = 16.dp, modifier = Modifier.padding(end = 3.dp))
                Text(text = rec.instruction, color = OnScrimMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }
        }
    }
}

private fun previewRecommendation(id: String, instruction: String, reason: String, direction: Direction) = Recommendation(
    id = id,
    category = MetricCategory.SUBJECT_PLACEMENT,
    priority = Priority.HIGH,
    confidence = 0.9f,
    severity = Severity.MEDIUM,
    title = instruction,
    instruction = instruction,
    reason = reason,
    direction = direction,
)

@Preview(name = "Coach level (with reason)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GuidanceBannerCoachPreview() {
    CompositionCoachTheme {
        GuidanceBanner(
            activeRecommendations = listOf(
                previewRecommendation("headroom", "Move slightly right", "Keeps eyes on the upper third", Direction.RIGHT),
                previewRecommendation("horizon", "Level the horizon", "The horizon is tilted 4°", Direction.ROTATE_COUNTER_CLOCKWISE),
            ),
            guidanceLevel = GuidanceLevel.COACH,
        )
    }
}

@Preview(name = "Balanced level", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GuidanceBannerBalancedPreview() {
    CompositionCoachTheme {
        GuidanceBanner(
            activeRecommendations = listOf(previewRecommendation("headroom", "Raise camera", "", Direction.UP)),
            guidanceLevel = GuidanceLevel.BALANCED,
        )
    }
}

@Preview(name = "Awaiting subject", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun GuidanceBannerAwaitingSubjectPreview() {
    CompositionCoachTheme {
        GuidanceBanner(
            activeRecommendations = listOf(
                Recommendation(
                    id = "intent.no_subject",
                    category = MetricCategory.SUBJECT_PLACEMENT,
                    priority = Priority.HIGH,
                    confidence = 1f,
                    severity = Severity.HIGH,
                    title = "Looking for a face",
                    instruction = "Move closer to your subject",
                ),
            ),
            guidanceLevel = GuidanceLevel.BALANCED,
            awaitingSubject = true,
        )
    }
}
