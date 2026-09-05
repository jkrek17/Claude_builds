package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private val FALLBACK_ANCHOR = NormalizedPoint(0.5f, 0.85f)
private val HORIZON_INDICATOR_ANCHOR = NormalizedPoint(0.5f, 0.14f)

/**
 * Subtle, always-on (non-debug) composition guidance drawn over the preview: a target ring for where the
 * subject should go, a short level indicator for the horizon, and a directional arrow following the primary
 * recommendation. Bounding boxes and regions are debug-only (see [DebugOverlay]) — this layer never draws them.
 */
@Composable
fun CompositionOverlay(composition: SmoothedComposition, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val metrics = composition.raw.metrics

        metrics.asSequence()
            .flatMap { it.geometry.asSequence() }
            .filterIsInstance<OverlayGeometry.TargetPoint>()
            .forEach { drawTargetRing(it.point) }

        val horizonMetric = metrics.firstOrNull { it.category == MetricCategory.HORIZON && it.applicable }
        val horizonLine = horizonMetric?.geometry?.filterIsInstance<OverlayGeometry.Line>()?.firstOrNull()

        val primary = composition.primaryRecommendation
        if (primary != null && (primary.direction == Direction.ROTATE_CLOCKWISE || primary.direction == Direction.ROTATE_COUNTER_CLOCKWISE)) {
            drawRotateGlyph(primary.direction, HORIZON_INDICATOR_ANCHOR)
        } else if (horizonLine != null) {
            drawLevelIndicator(horizonLine, isLevel = horizonMetric?.severity == Severity.NONE)
        }

        if (primary != null && primary.direction != Direction.NONE &&
            primary.direction != Direction.ROTATE_CLOCKWISE && primary.direction != Direction.ROTATE_COUNTER_CLOCKWISE
        ) {
            val anchor = composition.primarySubject?.anchorPoint ?: FALLBACK_ANCHOR
            drawDirectionalArrow(primary.direction, anchor)
        }
    }
}

private fun DrawScope.px(p: NormalizedPoint): Offset = Offset(p.x * size.width, p.y * size.height)

private fun DrawScope.drawTargetRing(point: NormalizedPoint) {
    val center = px(point)
    val radius = 14.dp.toPx()
    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = radius, center = center, style = Stroke(width = 2.dp.toPx()))
    drawCircle(color = Color.White.copy(alpha = 0.85f), radius = 2.dp.toPx(), center = center)
}

private fun DrawScope.drawLevelIndicator(line: OverlayGeometry.Line, isLevel: Boolean) {
    val angle = Math.toDegrees(atan2((line.end.y - line.start.y).toDouble(), (line.end.x - line.start.x).toDouble())).toFloat()
    val color = if (isLevel) Color(0xFF34D399) else Color.White.copy(alpha = 0.85f)
    val center = px(HORIZON_INDICATOR_ANCHOR)
    val halfLength = 22.dp.toPx()
    rotate(degrees = angle, pivot = center) {
        drawLine(color = color, start = center.copy(x = center.x - halfLength), end = center.copy(x = center.x + halfLength), strokeWidth = 2.dp.toPx())
    }
    // A fixed level reference so the tilt reads relative to something.
    drawLine(color = Color.White.copy(alpha = 0.25f), start = center.copy(x = center.x - halfLength), end = center.copy(x = center.x + halfLength), strokeWidth = 1.dp.toPx())
}

private fun DrawScope.drawDirectionalArrow(direction: Direction, anchor: NormalizedPoint) {
    val center = px(anchor)
    val color = Color.White.copy(alpha = 0.9f)
    val length = 28.dp.toPx()
    val stroke = 3.dp.toPx()
    val (dx, dy) = when (direction) {
        Direction.LEFT -> -1f to 0f
        Direction.RIGHT -> 1f to 0f
        Direction.UP -> 0f to -1f
        Direction.DOWN -> 0f to 1f
        Direction.CLOSER, Direction.BACK, Direction.ROTATE_CLOCKWISE, Direction.ROTATE_COUNTER_CLOCKWISE, Direction.NONE -> 0f to 0f
    }
    if (dx == 0f && dy == 0f) return
    val tip = Offset(center.x + dx * length, center.y + dy * length)
    drawLine(color = color, start = center, end = tip, strokeWidth = stroke)
    val headSize = 9.dp.toPx()
    val angle = atan2(dy, dx)
    val leftWing = Offset(tip.x - headSize * cos(angle - 0.5f), tip.y - headSize * sin(angle - 0.5f))
    val rightWing = Offset(tip.x - headSize * cos(angle + 0.5f), tip.y - headSize * sin(angle + 0.5f))
    drawLine(color = color, start = tip, end = leftWing, strokeWidth = stroke)
    drawLine(color = color, start = tip, end = rightWing, strokeWidth = stroke)
}

private fun DrawScope.drawRotateGlyph(direction: Direction, anchor: NormalizedPoint) {
    val center = px(anchor)
    val color = Color.White.copy(alpha = 0.9f)
    val radius = 16.dp.toPx()
    val sweep = if (direction == Direction.ROTATE_CLOCKWISE) 250f else -250f
    val startAngle = if (direction == Direction.ROTATE_CLOCKWISE) 20f else 160f
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
        style = Stroke(width = 2.5.dp.toPx()),
    )
    val endAngleRad = Math.toRadians((startAngle + sweep).toDouble())
    val tip = Offset(center.x + radius * cos(endAngleRad).toFloat(), center.y + radius * sin(endAngleRad).toFloat())
    val headSize = 7.dp.toPx()
    drawCircle(color = color, radius = headSize / 2f, center = tip)
}
