package com.compositioncoach.app.ui.camera

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.Recommendation

/** Portrait's width cap when [GuidanceBanner]'s caller doesn't override it (see `maxWidthOverride`). */
private const val PORTRAIT_WIDTH_FRACTION = 0.85f

/** Reserves height for one titleMedium line so a shorter/longer instruction never resizes the banner. */
private val HEADLINE_LINE_HEIGHT = 28.dp

/**
 * The instruction readout below the score: the primary recommendation crossfades as it changes (a vector
 * [DirectionIcon] to its left rather than a text glyph), an optional COACH-mode reason sits under it
 * (one line, ellipsized), and at most one secondary recommendation follows in smaller type (also one line,
 * ellipsized) — headline + reason + secondary is at most 3 lines total, deliberately compact so the banner
 * never sits on top of the subject (see the class-level "Bug 2" note in `app/README.md`).
 *
 * Never wider than [maxWidthOverride] if given, else [PORTRAIT_WIDTH_FRACTION] of the screen; never
 * overlaps [ScoreBadge] above it (the two are stacked by [CameraScreen], which also supplies
 * [maxWidthOverride] in landscape — a fraction of the *preview's height*, since that's the banner's long
 * axis once the stack is rotated 90/270 into place — see `RotatedChrome`/`chromeStackEdgeFor`).
 *
 * While [awaitingSubject] is true, [activeRecommendations] holds exactly the single find-subject
 * recommendation (see [com.compositioncoach.composition.model.SmoothedComposition.awaitingSubject]) and this
 * renders it prominently instead: its title as a small line, its instruction as the (one-line, ellipsized)
 * headline, no directional icon (the recommendation has no direction to point in).
 *
 * Does *not* rotate itself: `CameraScreen` wraps this together with [ScoreBadge] in one [RotatedChrome] so
 * the pair rotates and re-anchors as a single stack in landscape.
 */
@Composable
fun GuidanceBanner(
    activeRecommendations: List<Recommendation>,
    guidanceLevel: GuidanceLevel,
    awaitingSubject: Boolean = false,
    displayScore: Int = 0,
    isShootReady: Boolean = false,
    hasScene: Boolean = true,
    maxWidthOverride: Dp? = null,
    modifier: Modifier = Modifier,
) {
    // LocalWindowInfo.containerSize (px), not Configuration.screenWidthDp, per the accurate-window-size
    // guidance for Compose (Configuration's dp values round and vary with target SDK inset behaviour).
    val density = LocalDensity.current
    val screenWidthDp = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    val maxWidth = maxWidthOverride ?: (screenWidthDp * PORTRAIT_WIDTH_FRACTION)
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = GuidanceFormatter.awaitingSubjectHeadline(primary),
                color = OnScrim,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }

    val reason = GuidanceFormatter.reasonLine(primary, guidanceLevel)
    // At most one secondary recommendation, always — headline (1) + reason (0 or 1) + secondary (0 or 1)
    // is at most 3 lines total, whether or not a reason is showing (see the class doc's "Bug 2" note).
    val secondary = activeRecommendations.drop(1).take(1)
    // The full guidance sentence TalkBack announces: primary instruction, then the COACH-mode reason
    // (if shown), then any secondary recommendation — in the same order they're drawn.
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
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // COACH-only, one line, ellipsized rather than wrapped -- a long "why" sentence must not grow the
        // banner past its 3-line budget.
        reason?.let {
            Text(
                text = it,
                color = OnScrimMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        secondary.forEach { rec ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                DirectionIcon(direction = rec.direction, tint = OnScrimMuted, size = 16.dp, modifier = Modifier.padding(end = 3.dp))
                Text(
                    text = rec.instruction,
                    color = OnScrimMuted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

