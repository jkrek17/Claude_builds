package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.OnScrimMuted
import com.compositioncoach.app.ui.theme.Scrim
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SmoothedComposition
import kotlinx.coroutines.delay

/** How long a tapped secondary icon's explanation replaces the cue chip. */
private const val EXPLANATION_MS = 3_000L

/** Gap between the chevron and the chip attached to it, and the chevron's own inset from the frame edge. */
private val CHIP_GAP = 12.dp
private val CHEVRON_EDGE_INSET = 4.dp

/** How far along its edge the chrome stack slides when an edge chevron lands on that same edge. */
private const val CHROME_STACK_SHIFT_BIAS = -0.55f

/** The visible words on the cue chip and the sentence TalkBack hears for them; see [CueChip]. */
data class LiveChip(val text: String, val description: String = text)

/**
 * The whole coaching layer, laid over (and exactly the size of) the 4:3 preview: target ring and region
 * highlight on a canvas, exactly one primary cue (edge chevron / corner brackets / bubble level), one cue
 * chip, the optional score chip, and the secondary icon row. `docs/COACHING_UI.md` is the specification;
 * this composable is the part that decides *where* each piece goes, and [GuidanceFormatter] the part that
 * decides *what* each piece says.
 *
 * Two rules shape the layout:
 *  - The score chip, secondary icons and bubble level live at the **physical-top** edge, stacked inside one
 *    [RotatedChrome] so they rotate and re-anchor together. The cue chip joins that stack unless a chevron is
 *    showing, in which case it attaches to the chevron instead.
 *  - The chevron anchors on the screen edge its *physical* direction currently points at
 *    ([CueEdge.forDirection]), which is not the same screen edge in every hold.
 *
 * [pausedText] (an enhancement that stops live analysis) and [overrideChip] (a hold instruction, a group
 * blocker, the selfie "Arm out a little") take the chip's place at the physical top and every spatial cue
 * steps aside, so exactly one thing is said at a time. [cueAlpha] dims the overlay and score (a Motion/Night hold)
 * while the chip itself stays fully legible. Easy mode passes `showScore = false`,
 * `showSecondary = false` and `showReason = false`; Coach adds the number, the icon row and [GuidanceFormatter.reasonLine]
 * under the chip.
 *
 * When a `Raise` cue puts the chevron on the physical-top edge, where the chrome stack already lives, the
 * *stack* slides along that edge to make room. At the screen's own top edge there is nothing to do: the top
 * bar has already pushed the stack 60dp clear of it.
 */
@Composable
fun CoachingLayer(
    composition: SmoothedComposition,
    guidanceLevel: GuidanceLevel,
    showScore: Boolean,
    hasScene: Boolean,
    deviceRotationDegrees: Int,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
    pausedText: String? = null,
    showSecondary: Boolean = true,
    showReason: Boolean = false,
    overrideChip: LiveChip? = null,
    cueAlpha: Float = 1f,
) {
    val awaitingSubject = composition.awaitingSubject
    val quiet = pausedText != null || overrideChip != null
    val shootReady = GuidanceFormatter.effectiveShootReady(composition.isShootReady, awaitingSubject)
    val headline = if (quiet || awaitingSubject || shootReady) {
        null
    } else {
        GuidanceFormatter.headline(composition.activeRecommendations, guidanceLevel)
    }
    val cue = headline?.let { GuidanceFormatter.cueFor(it) }
    val secondary = if (quiet || awaitingSubject || !showSecondary) {
        emptyList()
    } else {
        GuidanceFormatter.secondary(composition.activeRecommendations, guidanceLevel)
    }

    // A tapped secondary icon replaces the cue chip with its one-line explanation for three seconds.
    var explanation by remember { mutableStateOf<Recommendation?>(null) }
    LaunchedEffect(explanation) {
        if (explanation != null) {
            delay(EXPLANATION_MS)
            explanation = null
        }
    }
    val pointAtSubject = rememberPointAtSubjectVisible(hasScene)

    val chip = when {
        pausedText != null -> LiveChip(pausedText)
        overrideChip != null -> overrideChip
        explanation != null -> LiveChip(GuidanceFormatter.explanationText(explanation!!))
        pointAtSubject -> LiveChip(GuidanceFormatter.POINT_AT_SUBJECT)
        awaitingSubject -> LiveChip(GuidanceFormatter.awaitingSubjectChipText(composition.primaryRecommendation))
        headline != null -> LiveChip(GuidanceFormatter.chipText(headline), GuidanceFormatter.chipDescription(headline))
        else -> null
    }
    val reason = if (showReason && explanation == null && chip != null) headline?.let { GuidanceFormatter.reasonLine(it) } else null

    val chromeEdge = CueEdge.physicalTop(deviceRotationDegrees)
    // Matches `chromeStackEdgePadding`: the screen's top edge has to clear `CameraTopBar`; every other
    // edge is flush at the standard 12dp cue inset.
    val chromeInset = if (chromeEdge == CueEdge.TOP) 60.dp else CHIP_GAP
    val chevronEdge = (cue as? PrimaryCue.EdgeChevron)?.let { CueEdge.forDirection(it.direction, deviceRotationDegrees) }
    // The chip goes with the chevron when there is one; otherwise it stacks under the score chip.
    val chipInStack = chevronEdge == null
    val chromeAlignment = if (chevronEdge == chromeEdge && chromeEdge != CueEdge.TOP) {
        chromeEdge.alignmentShiftedAlongEdge(CHROME_STACK_SHIFT_BIAS)
    } else {
        chromeEdge.toAlignment()
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val chipMaxWidth: Dp = minOf(maxWidth, maxHeight) * CUE_CHIP_MAX_WIDTH_FRACTION

        CoachingOverlay(
            composition = composition,
            deviceRotationDegrees = deviceRotationDegrees,
            animationsEnabled = animationsEnabled,
            modifier = Modifier.alpha(cueAlpha),
        )

        if (cue is PrimaryCue.CornerBrackets) {
            CornerBrackets(inward = cue.inward, animationsEnabled = animationsEnabled)
        }

        if (chevronEdge != null) {
            EdgeChevron(
                edge = chevronEdge,
                animationsEnabled = animationsEnabled,
                modifier = Modifier.align(chevronEdge.toAlignment()).cueEdgePadding(chevronEdge, CHEVRON_EDGE_INSET),
            )
            if (chip != null) {
                RotatedChrome(
                    deviceRotationDegrees = deviceRotationDegrees,
                    modifier = Modifier
                        .align(chevronEdge.toAlignment())
                        .cueEdgePadding(chevronEdge, CHEVRON_EDGE_INSET + CHEVRON_SIZE + CHIP_GAP),
                ) {
                    ChipWithReason(chip, reason, chipMaxWidth, animationsEnabled)
                }
            }
        }

        // The physical-top stack: score chip, bubble level (when it is the cue), the cue chip when no chevron
        // is carrying it, and the secondary icons.
        RotatedChrome(
            deviceRotationDegrees = deviceRotationDegrees,
            modifier = Modifier.align(chromeAlignment).cueEdgePadding(chromeEdge, chromeInset),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ScoreChip(
                    score = composition.displayScore,
                    showScore = showScore,
                    hasScene = hasScene,
                    isShootReady = composition.isShootReady,
                    awaitingSubject = awaitingSubject,
                    animationsEnabled = animationsEnabled,
                    modifier = Modifier.alpha(cueAlpha),
                )
                if (cue is PrimaryCue.BubbleLevel) {
                    BubbleLevel(
                        rollDegrees = HorizonRoll.degreesFor(composition.displayHorizon, deviceRotationDegrees),
                        animationsEnabled = animationsEnabled,
                    )
                }
                if (chipInStack && chip != null) {
                    ChipWithReason(chip, reason, chipMaxWidth, animationsEnabled)
                }
                SecondaryAdviceRow(
                    recommendations = secondary,
                    tappable = GuidanceFormatter.secondaryIconsTappable(guidanceLevel),
                    onExplain = { explanation = it },
                )
            }
        }
    }
}

/** The cue chip, and in Coach mode the one-line reason beneath it (bodySmall, muted, never wraps). */
@Composable
private fun ChipWithReason(chip: LiveChip, reason: String?, maxWidth: Dp, animationsEnabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CueChip(text = chip.text, description = chip.description, maxWidth = maxWidth, animationsEnabled = animationsEnabled)
        if (reason != null) {
            Text(
                text = reason,
                color = OnScrimMuted,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = maxWidth * 1.4f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Scrim)
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            )
        }
    }
}
