package com.compositioncoach.app.ui.camera

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.compositioncoach.app.ui.theme.Accent
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
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val REGION_FADE_MS = 300
private const val REGION_STROKE_ALPHA = 0.45f
private const val LEVEL_LINGER_MS = 1_500L
private const val LEVEL_FADE_MS = 400
private const val ARROW_PULSE_MS = 900
private val ARROW_PULSE_DISTANCE = 4.dp

private val FALLBACK_ANCHOR = NormalizedPoint(0.5f, 0.85f)
private val HORIZON_INDICATOR_ANCHOR = NormalizedPoint(0.5f, 0.14f)

/**
 * Subtle, always-on (non-debug) composition guidance drawn over the preview: a target ring for where the
 * subject should go (hidden once framing is within the dead zone — [Recommendation.direction] is
 * [Direction.NONE] or the shot is already shoot-ready), a short level indicator for the horizon that snaps
 * green and fades 1.5s after becoming level, a gently pulsing directional arrow following the primary
 * recommendation (anchored to [com.compositioncoach.composition.model.DetectedSubject.anchorPoint], which
 * is already the box centre for an object-kind subject — no special case needed here), a curved arrow
 * around the level indicator for rotation advice instead, and — while the headline recommendation carries a
 * `region` (background collisions, edge tension) and framing isn't shoot-ready yet — a fading 1dp/45%-white
 * rounded outline of that region. Detected-subject/object *bounding boxes* remain debug-only (see
 * [DebugGeometryOverlay]); this is the one region outline shown outside debug mode, deliberately restrained
 * (no fill, low alpha) so it reads as a hint, not a debug box.
 *
 * All geometry here is `FrameAnalysis`'s physical-up coordinates, not the (never-rotating) preview's own
 * frame; every point/rect/direction drawn goes through [OverlayMapper] with [deviceRotationDegrees] so it
 * lands in the right place and points the right way regardless of how the phone is physically held — see
 * [OverlayMapper]'s class KDoc and the "Arrow semantics" note on [drawDirectionalArrow].
 */
@Composable
fun CompositionOverlay(composition: SmoothedComposition, deviceRotationDegrees: Int = 0, modifier: Modifier = Modifier) {
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

    val metrics = composition.raw.metrics
    val horizonMetric = metrics.firstOrNull { it.category == MetricCategory.HORIZON && it.applicable }
    val horizonLine = horizonMetric?.geometry?.filterIsInstance<OverlayGeometry.Line>()?.firstOrNull()
    val isLevel = horizonMetric?.severity == Severity.NONE
    val showsRotateGlyph = primary != null &&
        (primary.direction == Direction.ROTATE_CLOCKWISE || primary.direction == Direction.ROTATE_COUNTER_CLOCKWISE)

    // The level indicator lingers for LEVEL_LINGER_MS once level, then fades out — tilting again before
    // that cancels the fade (a fresh `isLevel` key restarts this effect) and brings it back instantly.
    val levelAlpha = remember { Animatable(if (horizonLine != null) 1f else 0f) }
    LaunchedEffect(horizonLine != null, isLevel, showsRotateGlyph) {
        when {
            horizonLine == null || showsRotateGlyph -> levelAlpha.snapTo(0f)
            isLevel -> {
                levelAlpha.snapTo(1f)
                delay(LEVEL_LINGER_MS)
                levelAlpha.animateTo(0f, tween(LEVEL_FADE_MS))
            }
            else -> levelAlpha.snapTo(1f)
        }
    }
    val levelColor by animateColorAsState(if (isLevel) Accent.Ready else Color.White.copy(alpha = 0.85f), label = "levelColor")

    // A directional arrow only makes sense while there is an active position/closer/back recommendation
    // outside the dead zone (Direction.NONE) and the shot isn't already ready to fire.
    val showsArrow = primary != null && !composition.isShootReady &&
        primary.direction != Direction.NONE && !showsRotateGlyph
    val infiniteTransition = rememberInfiniteTransition(label = "arrowPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(ARROW_PULSE_MS), repeatMode = RepeatMode.Reverse),
        label = "arrowPulse",
    )

    // The target ring is the same "dead zone" affordance as the arrow: nothing to correct, nothing to show.
    val showsTargetRing = primary != null && !composition.isShootReady && primary.direction != Direction.NONE

    Canvas(modifier = modifier.fillMaxSize()) {
        if (showsTargetRing) {
            metrics.asSequence()
                .flatMap { it.geometry.asSequence() }
                .filterIsInstance<OverlayGeometry.TargetPoint>()
                .forEach { drawTargetRing(it.point, deviceRotationDegrees) }
        }

        if (showsRotateGlyph) {
            drawRotateGlyph(primary!!.direction, HORIZON_INDICATOR_ANCHOR, deviceRotationDegrees)
        } else if (levelAlpha.value > 0f && horizonLine != null) {
            drawLevelIndicator(horizonLine, levelColor, levelAlpha.value, deviceRotationDegrees)
        }

        if (showsArrow) {
            val anchor = composition.primarySubject?.anchorPoint ?: FALLBACK_ANCHOR
            drawDirectionalArrow(primary!!.direction, anchor, pulse * ARROW_PULSE_DISTANCE.toPx(), deviceRotationDegrees)
        }

        // Drawn last so it never sits under the arrow/target ring; regionAlpha (animated above) is what
        // actually fades it in/out, so drawing with 0 alpha here after the headline moves on is harmless.
        if (regionAlpha > 0f) lastRegion?.let { drawRegionHighlight(it, regionAlpha, deviceRotationDegrees) }
    }
}

/** Every physical-up point drawn on this overlay goes through [OverlayMapper] so rotation is never skipped. */
private fun DrawScope.px(p: NormalizedPoint, deviceRotationDegrees: Int): Offset {
    val (x, y) = OverlayMapper.toPx(p, deviceRotationDegrees, size.width, size.height)
    return Offset(x, y)
}

/**
 * A restrained rounded outline over a recommendation's [OverlayGeometry]-adjacent `region` (background
 * collisions, edge tension) while it is the headline: 1dp stroke, 45% white, no fill, per the spec — never
 * more prominent than that, since the arrow/ring already carry the actionable guidance.
 */
private fun DrawScope.drawRegionHighlight(region: NormalizedRect, alpha: Float, deviceRotationDegrees: Int) {
    if (alpha <= 0f) return
    val px = OverlayMapper.toPxRect(region, deviceRotationDegrees, size.width, size.height)
    drawRoundRect(
        color = Color.White.copy(alpha = REGION_STROKE_ALPHA * alpha),
        topLeft = Offset(px.left, px.top),
        size = androidx.compose.ui.geometry.Size(px.width, px.height),
        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** 18dp ring (9dp radius), 1.5dp stroke, 70% white — restrained on purpose, the arrow carries the advice. */
private fun DrawScope.drawTargetRing(point: NormalizedPoint, deviceRotationDegrees: Int) {
    val center = px(point, deviceRotationDegrees)
    drawCircle(color = Color.White.copy(alpha = 0.7f), radius = 9.dp.toPx(), center = center, style = Stroke(width = 1.5.dp.toPx()))
}

/**
 * The angle is derived from the horizon *line*'s two endpoints mapped through [OverlayMapper] (rather
 * than rotating the raw physical-space angle number directly) so a 90/180/270-degree device rotation is
 * automatically folded in exactly the same way every other point on this overlay is rotated.
 */
private fun DrawScope.drawLevelIndicator(line: OverlayGeometry.Line, color: Color, alpha: Float, deviceRotationDegrees: Int) {
    val start = px(line.start, deviceRotationDegrees)
    val end = px(line.end, deviceRotationDegrees)
    val angle = Math.toDegrees(atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())).toFloat()
    val center = px(HORIZON_INDICATOR_ANCHOR, deviceRotationDegrees)
    val halfLength = 22.dp.toPx()
    rotate(degrees = angle, pivot = center) {
        drawLine(
            color = color.copy(alpha = color.alpha * alpha),
            start = center.copy(x = center.x - halfLength),
            end = center.copy(x = center.x + halfLength),
            strokeWidth = 2.dp.toPx(),
        )
    }
    // A fixed level reference so the tilt reads relative to something.
    drawLine(
        color = Color.White.copy(alpha = 0.25f * alpha),
        start = center.copy(x = center.x - halfLength),
        end = center.copy(x = center.x + halfLength),
        strokeWidth = 1.dp.toPx(),
    )
}

/**
 * A clean, pulsing arrow (24-32dp long) pointing the way the *camera* should move — [Direction.RIGHT]
 * points right, matching `ReframeVector`'s "dx > 0 = pan right" convention, so the arrow always agrees
 * with which way the photographer should actually move the phone. White with a thin dark outline so it
 * reads over any background; [pulsePx] gently translates it along its own direction, 0..4dp.
 *
 * ### Arrow semantics under device rotation
 * [direction] is physical-up ("move toward the physical right"); when the phone is held rotated, the
 * *on-screen* arrow must be rotated by [deviceRotationDegrees] so it still visually points the physically
 * correct way — [OverlayMapper.rotateVectorToDisplay] does that (a direction, not a point: no
 * translation), same rotation every other element on this overlay goes through.
 */
private fun DrawScope.drawDirectionalArrow(direction: Direction, anchor: NormalizedPoint, pulsePx: Float, deviceRotationDegrees: Int) {
    val base = px(anchor, deviceRotationDegrees)
    val length = 28.dp.toPx()
    val stroke = 3.dp.toPx()
    val (rawDx, rawDy) = when (direction) {
        Direction.LEFT -> -1f to 0f
        Direction.RIGHT -> 1f to 0f
        Direction.UP -> 0f to -1f
        Direction.DOWN -> 0f to 1f
        Direction.CLOSER, Direction.BACK, Direction.ROTATE_CLOCKWISE, Direction.ROTATE_COUNTER_CLOCKWISE, Direction.NONE -> 0f to 0f
    }
    if (rawDx == 0f && rawDy == 0f) return
    val (dx, dy) = OverlayMapper.rotateVectorToDisplay(rawDx, rawDy, deviceRotationDegrees)
    val center = Offset(base.x + dx * pulsePx, base.y + dy * pulsePx)
    val tip = Offset(center.x + dx * length, center.y + dy * length)
    val angle = atan2(dy, dx)
    val headSize = 9.dp.toPx()
    val leftWing = Offset(tip.x - headSize * cos(angle - 0.5f), tip.y - headSize * sin(angle - 0.5f))
    val rightWing = Offset(tip.x - headSize * cos(angle + 0.5f), tip.y - headSize * sin(angle + 0.5f))

    // Outline pass first (dark, slightly thicker), then the white arrow on top — legible on any photo.
    val outline = Color.Black.copy(alpha = 0.55f)
    val outlineStroke = stroke + 2.dp.toPx()
    drawLine(color = outline, start = center, end = tip, strokeWidth = outlineStroke)
    drawLine(color = outline, start = tip, end = leftWing, strokeWidth = outlineStroke)
    drawLine(color = outline, start = tip, end = rightWing, strokeWidth = outlineStroke)

    val fill = Color.White
    drawLine(color = fill, start = center, end = tip, strokeWidth = stroke)
    drawLine(color = fill, start = tip, end = leftWing, strokeWidth = stroke)
    drawLine(color = fill, start = tip, end = rightWing, strokeWidth = stroke)
}

private fun DrawScope.drawRotateGlyph(direction: Direction, anchor: NormalizedPoint, deviceRotationDegrees: Int) {
    val center = px(anchor, deviceRotationDegrees)
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

@Preview(name = "Directional arrow (pan right)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CompositionOverlayArrowPreview() {
    val recommendation = Recommendation(
        id = "subject.right",
        category = MetricCategory.SUBJECT_PLACEMENT,
        priority = Priority.HIGH,
        confidence = 0.9f,
        severity = Severity.MEDIUM,
        title = "Move slightly right",
        instruction = "Move slightly right",
        direction = Direction.RIGHT,
    )
    val raw = CompositionResult.empty().copy(recommendations = listOf(recommendation))
    CompositionCoachTheme {
        CompositionOverlay(
            composition = SmoothedComposition(60, listOf(recommendation), false, SceneClassification(SceneType.PORTRAIT, 0.9f), null, raw),
        )
    }
}
