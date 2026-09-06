package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition
import kotlinx.coroutines.delay

/** How long a tapped secondary icon's explanation replaces the cue chip. */
private const val EXPLANATION_MS = 3_000L

/** How long "Point at a subject" lingers after a subject actually appears, before fading for good. */
private const val POINT_AT_SUBJECT_LINGER_MS = 3_000L

/** Gap between the chevron and the chip attached to it, and the chevron's own inset from the frame edge. */
private val CHIP_GAP = 12.dp
private val CHEVRON_EDGE_INSET = 4.dp

/** How far along its edge the chrome stack slides when an edge chevron lands on that same edge. */
private const val CHROME_STACK_SHIFT_BIAS = -0.55f

/**
 * The whole coaching layer, laid over (and exactly the size of) the 4:3 preview: target ring and region
 * highlight on a canvas, exactly one primary cue (edge chevron / corner brackets / bubble level), one cue
 * chip, the optional score chip, and the secondary icon row. `docs/COACHING_UI.md` is the specification;
 * this composable is the part that decides *where* each piece goes, and [GuidanceFormatter] the part that
 * decides *what* each piece says.
 *
 * Two rules shape the layout:
 *  - The score chip, secondary icons and bubble level live at the **physical-top** edge, stacked inside one
 *    [RotatedChrome] so they rotate and re-anchor together (the same treatment the old badge/banner stack
 *    had). The cue chip joins that stack unless a chevron is showing, in which case it attaches to the
 *    chevron instead.
 *  - The chevron anchors on the screen edge its *physical* direction currently points at
 *    ([CueEdge.forDirection]), which is not the same screen edge in every hold.
 *
 * When those two collide — a `Raise` cue puts the chevron on the physical-top edge, where the chrome stack
 * already lives — the *stack* slides along that edge to make room, rather than the chevron being pushed
 * off the edge it exists to point out of. At the screen's own top edge there is nothing to do: the top bar
 * has already pushed the stack 60dp clear of it.
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
) {
    val awaitingSubject = composition.awaitingSubject
    val shootReady = GuidanceFormatter.effectiveShootReady(composition.isShootReady, awaitingSubject)
    val headline = if (awaitingSubject || shootReady) {
        null
    } else {
        GuidanceFormatter.headline(composition.activeRecommendations, guidanceLevel)
    }
    val cue = headline?.let { GuidanceFormatter.cueFor(it) }
    val secondary = if (awaitingSubject) {
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
        explanation != null -> CueChipContent(
            text = GuidanceFormatter.explanationText(explanation!!),
            description = GuidanceFormatter.explanationText(explanation!!),
        )
        pointAtSubject -> CueChipContent(GuidanceFormatter.POINT_AT_SUBJECT, GuidanceFormatter.POINT_AT_SUBJECT)
        awaitingSubject -> GuidanceFormatter.awaitingSubjectChipText(composition.primaryRecommendation)
            .let { CueChipContent(it, it) }
        headline != null -> CueChipContent(
            text = GuidanceFormatter.chipText(headline),
            description = GuidanceFormatter.chipDescription(headline),
        )
        else -> null
    }

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
                    CueChip(
                        text = chip.text,
                        description = chip.description,
                        maxWidth = chipMaxWidth,
                        animationsEnabled = animationsEnabled,
                    )
                }
            }
        }

        // The physical-top stack: score chip, bubble level (when it is the cue), secondary icons, and the
        // cue chip when no chevron is carrying it.
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
                )
                if (cue is PrimaryCue.BubbleLevel) {
                    BubbleLevel(
                        rollDegrees = HorizonRoll.degreesFor(composition.displayHorizon, deviceRotationDegrees),
                        animationsEnabled = animationsEnabled,
                    )
                }
                if (chipInStack && chip != null) {
                    CueChip(
                        text = chip.text,
                        description = chip.description,
                        maxWidth = chipMaxWidth,
                        animationsEnabled = animationsEnabled,
                    )
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

/** The visible words and the sentence TalkBack hears for them; see [CueChip]. */
private data class CueChipContent(val text: String, val description: String)

/**
 * "Point at a subject" shows until the engine classifies a scene, then lingers for three seconds so the
 * hand-off into real guidance doesn't read as the chip blinking out, and never comes back until the scene
 * is lost again.
 */
@Composable
private fun rememberPointAtSubjectVisible(hasScene: Boolean): Boolean {
    var visible by remember { mutableStateOf(!hasScene) }
    LaunchedEffect(hasScene) {
        if (hasScene) {
            delay(POINT_AT_SUBJECT_LINGER_MS)
            visible = false
        } else {
            visible = true
        }
    }
    return visible
}

internal fun previewRecommendation(
    direction: Direction,
    category: MetricCategory = MetricCategory.SUBJECT_PLACEMENT,
    title: String = "Move slightly right",
    severity: Severity = Severity.MEDIUM,
) = Recommendation(
    id = "preview.${category.name}.${direction.name}",
    category = category,
    priority = Priority.HIGH,
    confidence = 0.9f,
    severity = severity,
    title = title,
    instruction = title,
    direction = direction,
)

@Preview(name = "Coaching layer (portrait, move right)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CoachingLayerPortraitPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) {
            CoachingLayer(
                composition = SmoothedComposition.EMPTY.copy(
                    displayScore = 74,
                    activeRecommendations = listOf(previewRecommendation(Direction.RIGHT)),
                    scene = SceneClassification(SceneType.PORTRAIT, 0.9f),
                ),
                guidanceLevel = GuidanceLevel.BALANCED,
                showScore = true,
                hasScene = true,
                deviceRotationDegrees = 0,
                animationsEnabled = false,
            )
        }
    }
}

@Preview(name = "Coaching layer (rotation 90, move right)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CoachingLayerRotatedPreview() {
    CompositionCoachTheme {
        Box(Modifier.fillMaxSize()) {
            CoachingLayer(
                composition = SmoothedComposition.EMPTY.copy(
                    displayScore = 74,
                    activeRecommendations = listOf(previewRecommendation(Direction.RIGHT)),
                    scene = SceneClassification(SceneType.PORTRAIT, 0.9f),
                ),
                guidanceLevel = GuidanceLevel.COACH,
                showScore = true,
                hasScene = true,
                deviceRotationDegrees = 90,
                animationsEnabled = false,
            )
        }
    }
}
