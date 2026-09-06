package com.compositioncoach.app.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.app.ui.theme.Motion
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.composition.model.OverlayGeometry
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2

/** The rotating line's length, per `docs/COACHING_UI.md`. */
private val LEVEL_LINE_LENGTH = 48.dp

/** How long the level lingers after it snaps green, before fading away. */
private const val LEVEL_LINGER_MS = 1_500L

/** Pure roll maths behind [BubbleLevel], so the aspect correction below is unit-testable off-device. */
object HorizonRoll {

    /** Within this many degrees of horizontal the hold counts as level and the indicator snaps green. */
    const val LEVEL_TOLERANCE_DEGREES = 1.5f

    /**
     * The angle, in degrees clockwise, to rotate the level's line by so it matches [line] — the engine's
     * smoothed horizon, expressed in *physical-up normalized* coordinates.
     *
     * Two corrections are folded in. Normalized coordinates are not square, so a line's normalized slope
     * is not its on-screen slope: the endpoints are scaled back to the preview's real proportions first
     * (using [previewAspect], width / height of the preview box). And in a 90/270 hold the physical frame
     * is laid across the portrait-locked preview sideways, so the two axes' extents swap — the same swap
     * [RotatedChromeMath.swapsAxes] applies to the chrome this indicator lives inside.
     *
     * Returns 0 when there is no horizon signal: a level line is the honest default, and the caller hides
     * the indicator entirely in that case anyway.
     */
    fun degreesFor(line: OverlayGeometry.Line?, deviceRotationDegrees: Int, previewAspect: Float = 3f / 4f): Float {
        if (line == null) return 0f
        val swap = RotatedChromeMath.swapsAxes(deviceRotationDegrees)
        val xScale = if (swap) 1f else previewAspect
        val yScale = if (swap) previewAspect else 1f
        val dx = (line.end.x - line.start.x) * xScale
        val dy = (line.end.y - line.start.y) * yScale
        if (dx == 0f && dy == 0f) return 0f
        val degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        // A horizon is a line, not a ray: -179 degrees and +1 degree are the same tilt, so fold the
        // answer into (-90, 90] rather than letting an endpoint-order flip read as a 180-degree spin.
        return when {
            degrees > 90f -> degrees - 180f
            degrees <= -90f -> degrees + 180f
            else -> degrees
        }
    }

    fun isLevel(rollDegrees: Float): Boolean = abs(rollDegrees) <= LEVEL_TOLERANCE_DEGREES
}

/**
 * The primary cue for a ROTATE_* headline: fixed tick marks with a 48dp line rotated by the smoothed roll,
 * snapping to ready-green the moment the hold is level and fading out 1.5s later. No chevron accompanies
 * it — a tilt has no edge to point at.
 *
 * Laid out at the physical-top edge inside the chrome stack's [RotatedChrome], so "upright" here already
 * means upright to the photographer and [rollDegrees] is the *residual* tilt on top of that — which is
 * exactly what [HorizonRoll.degreesFor] computes.
 *
 * Decorative; the chip carries the meaning ("Level") for TalkBack.
 */
@Composable
fun BubbleLevel(
    rollDegrees: Float,
    animationsEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val level = HorizonRoll.isLevel(rollDegrees)
    val color by animateColorAsState(
        targetValue = if (level) Accent.Ready else OnScrim,
        animationSpec = tween(motionDuration(Motion.STATE_CHANGE_MS, animationsEnabled)),
        label = "levelColor",
    )
    val angle by animateFloatAsState(
        targetValue = rollDegrees,
        animationSpec = tween(motionDuration(Motion.STATE_CHANGE_MS, animationsEnabled)),
        label = "levelAngle",
    )
    // Lingers while tilted; once level it holds for 1.5s and then fades. Tilting again restarts this
    // effect (a fresh `level` key) and brings it straight back.
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(level, animationsEnabled) {
        if (!level) {
            alpha.snapTo(1f)
        } else {
            alpha.snapTo(1f)
            delay(LEVEL_LINGER_MS)
            alpha.animateTo(0f, tween(motionDuration(Motion.STATE_CHANGE_MS * 2, animationsEnabled)))
        }
    }

    Canvas(
        modifier = modifier
            .width(LEVEL_LINE_LENGTH + 24.dp)
            .height(20.dp)
            .clearAndSetSemantics {},
    ) {
        val a = alpha.value
        if (a <= 0f) return@Canvas
        val center = Offset(size.width / 2f, size.height / 2f)
        val half = LEVEL_LINE_LENGTH.toPx() / 2f
        val tick = 6.dp.toPx()
        // Fixed reference ticks: the level the line is being compared against.
        listOf(-1f, 1f).forEach { side ->
            val x = center.x + side * (half + 6.dp.toPx())
            drawLine(
                color = OnScrim.copy(alpha = 0.4f * a),
                start = Offset(x, center.y - tick / 2f),
                end = Offset(x, center.y + tick / 2f),
                strokeWidth = 1.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        rotate(degrees = angle, pivot = center) {
            drawLine(
                color = color.copy(alpha = a),
                start = Offset(center.x - half, center.y),
                end = Offset(center.x + half, center.y),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

@Preview(name = "Bubble level (tilted)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun BubbleLevelTiltedPreview() {
    CompositionCoachTheme { BubbleLevel(rollDegrees = 9f, animationsEnabled = false) }
}

@Preview(name = "Bubble level (level)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun BubbleLevelLevelPreview() {
    CompositionCoachTheme { BubbleLevel(rollDegrees = 0f, animationsEnabled = false) }
}
