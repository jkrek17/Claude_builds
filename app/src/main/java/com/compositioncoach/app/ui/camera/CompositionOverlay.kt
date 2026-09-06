package com.compositioncoach.app.ui.camera

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.CompositionMetric
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.OverlayGeometry
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val REGION_FADE_MS = 300
private const val REGION_STROKE_ALPHA = 0.45f

private val FALLBACK_ANCHOR = NormalizedPoint(0.5f, 0.85f)
private val HORIZON_INDICATOR_ANCHOR = NormalizedPoint(0.5f, 0.14f)

/**
 * Subtle, always-on (non-debug) composition guidance drawn over the preview: a target ring for where the
 * subject should go, a short level indicator for the horizon, a directional arrow following the primary
 * recommendation (anchored to [com.compositioncoach.composition.model.DetectedSubject.anchorPoint], which
 * is already the box centre for an object-kind subject — no special case needed here), and — while the
 * headline recommendation carries a `region` (background collisions, edge tension) and framing isn't
 * shoot-ready yet — a fading 1dp/45%-white rounded outline of that region. Detected-subject/object
 * *bounding boxes* remain debug-only (see [DebugGeometryOverlay]); this is the one region outline shown
 * outside debug mode, deliberately restrained (no fill, low alpha) so it reads as a hint, not a debug box.
 */
@Composable
fun CompositionOverlay(composition: SmoothedComposition, modifier: Modifier = Modifier) {
    val primary = composition.primaryRecommendation
    // The region highlight only makes sense while there is still something to fix, so it never shows in
    // shoot-ready state (per the spec: guidance affordances are never drawn once framing is already good).
    val region = primary?.region.takeIf { !composition.isShootReady }
    val regionAlpha by animateFloatAsState(
        targetValue = if (region != null) 1f else 0f,
        animationSpec = tween(REGION_FADE_MS),
        label = "regionHighlightAlpha",
    )
    // Remembers the last non-null region so the fade-out (regionAlpha -> 0, above) has something to
    // animate away from instead of the rect disappearing the instant the headline stops having one.
    var lastRegion by remember { mutableStateOf<NormalizedRect?>(null) }
    if (region != null) lastRegion = region

    Canvas(modifier = modifier.fillMaxSize()) {
        val metrics = composition.raw.metrics

        metrics.asSequence()
            .flatMap { it.geometry.asSequence() }
            .filterIsInstance<OverlayGeometry.TargetPoint>()
            .forEach { drawTargetRing(it.point) }

        val horizonMetric = metrics.firstOrNull { it.category == MetricCategory.HORIZON && it.applicable }
        val horizonLine = horizonMetric?.geometry?.filterIsInstance<OverlayGeometry.Line>()?.firstOrNull()

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

        // Drawn last so it never sits under the arrow/target ring; regionAlpha (animated above) is what
        // actually fades it in/out, so drawing with 0 alpha here after the headline moves on is harmless.
        if (regionAlpha > 0f) lastRegion?.let { drawRegionHighlight(it, regionAlpha) }
    }
}

private fun DrawScope.px(p: NormalizedPoint): Offset = Offset(p.x * size.width, p.y * size.height)

/**
 * A restrained rounded outline over a recommendation's [OverlayGeometry]-adjacent `region` (background
 * collisions, edge tension) while it is the headline: 1dp stroke, 45% white, no fill, per the spec — never
 * more prominent than that, since the arrow/ring already carry the actionable guidance.
 */
private fun DrawScope.drawRegionHighlight(region: NormalizedRect, alpha: Float) {
    if (alpha <= 0f) return
    val topLeft = Offset(region.left * size.width, region.top * size.height)
    val rectSize = androidx.compose.ui.geometry.Size(region.width * size.width, region.height * size.height)
    drawRoundRect(
        color = Color.White.copy(alpha = REGION_STROKE_ALPHA * alpha),
        topLeft = topLeft,
        size = rectSize,
        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
        style = Stroke(width = 1.dp.toPx()),
    )
}

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

@Preview(name = "Region highlight (background collision)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CompositionOverlayRegionPreview() {
    val recommendation = Recommendation(
        id = "background.pole",
        category = MetricCategory.BACKGROUND_DISTRACTION,
        priority = Priority.HIGH,
        confidence = 0.8f,
        severity = Severity.MEDIUM,
        title = "Distracting background",
        instruction = "Reframe to clear the pole behind your subject",
        direction = Direction.NONE,
        region = NormalizedRect(0.6f, 0.05f, 0.75f, 0.5f),
    )
    val raw = CompositionResult.empty().copy(
        metrics = listOf(CompositionMetric(MetricCategory.BACKGROUND_DISTRACTION, "BackgroundDistractionAnalyzer", 0.4f, 0.8f, Severity.MEDIUM, recommendation = recommendation)),
        recommendations = listOf(recommendation),
    )
    CompositionCoachTheme {
        CompositionOverlay(
            composition = SmoothedComposition(70, listOf(recommendation), false, SceneClassification(SceneType.PORTRAIT, 0.9f), null, raw),
        )
    }
}
