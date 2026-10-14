package com.compositioncoach.app.ui.camera

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.OnScrim
import com.compositioncoach.app.ui.theme.scoreColor

/**
 * The shutter's outer ring, and the review card's score ring, are the same drawing: an arc that sweeps
 * clockwise with the smoothed score, starting at 8 o'clock and closing the full circle at 100
 * (`docs/COACHING_UI.md`, "Readiness arc"). The maths is pure so the sweep and the tier colour are
 * unit-testable without a Canvas (see `ReadinessArcTest`); [DrawScope.drawReadinessArc] is the one
 * drawing routine both callers share, so the two rings can never drift apart.
 */
object ReadinessArc {

    /**
     * Compose measures arc angles from 3 o'clock, clockwise-positive, so clock hour `h` is
     * `h * 30 - 90` degrees. The spec's 8 o'clock start is therefore 150 degrees.
     */
    const val START_ANGLE_DEGREES = 150f

    /** A full turn: the sweep at score 100. */
    const val FULL_SWEEP_DEGREES = 360f

    /** The clockwise sweep for [score], clamped to 0..100 so a stale or out-of-range score can't overdraw. */
    fun sweepDegrees(score: Int): Float = score.coerceIn(0, 100) / 100f * FULL_SWEEP_DEGREES

    /**
     * The arc's colour: the score tier's colour (white below 70, amber to 87, ready-green at 88+), except
     * that shoot-ready always paints the full ring ready-green even if the tier calculation disagrees —
     * the engine's own `isShootReady` is the authority on that state, not the number.
     */
    fun color(score: Int, isShootReady: Boolean): Color =
        if (isShootReady) Accent.Ready else scoreColor(GuidanceFormatter.scoreTier(score))
}

/** The unfilled remainder of the ring, so the shutter still reads as a ring at a low score. */
private const val TRACK_ALPHA = 0.25f

/**
 * Draws the readiness arc for [score] filling the whole of this [DrawScope], inset by half of
 * [strokeWidthPx] so the stroke stays inside the bounds. [alpha] dims the whole ring (the awaiting-subject
 * state drops it to 30%); [progressOverride], when given, replaces the score-derived sweep fraction so a
 * caller can animate the sweep independently of the number it is showing.
 */
fun DrawScope.drawReadinessArc(
    score: Int,
    isShootReady: Boolean,
    strokeWidthPx: Float,
    alpha: Float = 1f,
    progressOverride: Float? = null,
) {
    // Inset by half the stroke so the ring's outer edge lands exactly on this scope's bounds.
    val diameter = size.minDimension - strokeWidthPx
    if (diameter <= 0f) return
    val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
    val arcSize = Size(diameter, diameter)
    val stroke = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
    val color = ReadinessArc.color(score, isShootReady)
    val sweep = progressOverride?.coerceIn(0f, 1f)?.times(ReadinessArc.FULL_SWEEP_DEGREES)
        ?: ReadinessArc.sweepDegrees(score)

    drawCircle(
        color = OnScrim.copy(alpha = TRACK_ALPHA * alpha),
        radius = diameter / 2f,
        center = Offset(topLeft.x + diameter / 2f, topLeft.y + diameter / 2f),
        style = stroke,
    )
    if (sweep <= 0f) return
    drawArc(
        color = color.copy(alpha = color.alpha * alpha),
        startAngle = ReadinessArc.START_ANGLE_DEGREES,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = stroke,
    )
}
