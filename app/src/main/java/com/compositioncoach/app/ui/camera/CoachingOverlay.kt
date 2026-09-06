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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.Direction
import com.compositioncoach.composition.model.MetricCategory
import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.Priority
import com.compositioncoach.composition.model.Recommendation
import com.compositioncoach.composition.model.SceneClassification
import com.compositioncoach.composition.model.SceneType
import com.compositioncoach.composition.model.Severity
import com.compositioncoach.composition.model.SmoothedComposition

private const val REGION_FADE_MS = 300
private const val REGION_STROKE_ALPHA = 0.45f
private const val TARGET_RING_ALPHA = 0.7f

/**
 * The two frame-anchored marks the coaching layer draws straight onto the preview: the target ring (where
 * the coach wants the subject) and the region highlight (what is in the way). Everything else in the
 * layer — chevrons, brackets, the level, the chips — is a positioned composable, not a canvas mark.
 *
 * Nothing here sits over the centre of the frame except the ring, per the spec's first principle. The old
 * subject-anchored directional arrow is deliberately gone: the edge chevron carries direction now, and an
 * arrow floating on the subject was the busiest thing on the screen.
 *
 * All geometry is `FrameAnalysis`'s physical-up coordinates, so every point and rect goes through
 * [OverlayMapper] with [deviceRotationDegrees] — the same single rotation path every other overlay uses.
 */
@Composable
fun CoachingOverlay(
    composition: SmoothedComposition,
    deviceRotationDegrees: Int = 0,
    animationsEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val headline = composition.primaryRecommendation
    // A region only makes sense while there is still something to fix, so it never shows once ready.
    val region = (composition.displayRegion ?: headline?.region).takeIf { !composition.isShootReady }
    val regionAlpha by animateFloatAsState(
        targetValue = if (region != null) 1f else 0f,
        animationSpec = tween(motionDuration(REGION_FADE_MS, animationsEnabled)),
        label = "regionHighlightAlpha",
    )
    // Remembers the last non-null region so the fade-out has something to animate away from, instead of
    // the rect vanishing the instant the headline stops carrying one.
    var lastRegion by remember { mutableStateOf<NormalizedRect?>(null) }
    if (region != null) lastRegion = region

    // The ring is placement guidance: it shows only while the headline is asking for a reframe and the
    // engine has a smoothed target outside its dead zone (`displayTarget` is null inside it).
    val target = composition.displayTarget.takeIf {
        headline != null && !composition.isShootReady && headline.direction != Direction.NONE
    }

    Canvas(modifier = modifier.fillMaxSize().clearAndSetSemantics {}) {
        if (target != null) drawTargetRing(target, deviceRotationDegrees)
        if (regionAlpha > 0f) lastRegion?.let { drawRegionHighlight(it, regionAlpha, deviceRotationDegrees) }
    }
}

/** 18dp ring (9dp radius), 1.5dp stroke, 70% white — restrained on purpose; the chevron carries the advice. */
private fun DrawScope.drawTargetRing(point: NormalizedPoint, deviceRotationDegrees: Int) {
    val (x, y) = OverlayMapper.toPx(point, deviceRotationDegrees, size.width, size.height)
    drawCircle(
        color = Color.White.copy(alpha = TARGET_RING_ALPHA),
        radius = 9.dp.toPx(),
        center = Offset(x, y),
        style = Stroke(width = 1.5.dp.toPx()),
    )
}

/** 1dp, 45% white, rounded — a hint at what to move away from, never a debug box. */
private fun DrawScope.drawRegionHighlight(region: NormalizedRect, alpha: Float, deviceRotationDegrees: Int) {
    val px = OverlayMapper.toPxRect(region, deviceRotationDegrees, size.width, size.height)
    drawRoundRect(
        color = Color.White.copy(alpha = REGION_STROKE_ALPHA * alpha),
        topLeft = Offset(px.left, px.top),
        size = Size(px.width, px.height),
        cornerRadius = CornerRadius(10.dp.toPx(), 10.dp.toPx()),
        style = Stroke(width = 1.dp.toPx()),
    )
}

private fun previewComposition(): SmoothedComposition {
    val recommendation = Recommendation(
        id = "background.pole",
        category = MetricCategory.BACKGROUND_DISTRACTION,
        priority = Priority.HIGH,
        confidence = 0.8f,
        severity = Severity.MEDIUM,
        title = "Distracting background",
        instruction = "Reframe to clear the pole behind your subject",
        direction = Direction.RIGHT,
        region = NormalizedRect(0.6f, 0.05f, 0.75f, 0.5f),
    )
    return SmoothedComposition(
        displayScore = 62,
        activeRecommendations = listOf(recommendation),
        isShootReady = false,
        scene = SceneClassification(SceneType.PORTRAIT, 0.9f),
        primarySubject = null,
        raw = CompositionResult.empty().copy(recommendations = listOf(recommendation)),
        displayTarget = NormalizedPoint(0.33f, 0.4f),
        displayRegion = NormalizedRect(0.6f, 0.05f, 0.75f, 0.5f),
    )
}

@Preview(name = "Coaching overlay (portrait)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CoachingOverlayPreview() {
    CompositionCoachTheme {
        CoachingOverlay(composition = previewComposition(), deviceRotationDegrees = 0, animationsEnabled = false)
    }
}

@Preview(name = "Coaching overlay (rotation 90)", showBackground = true, backgroundColor = 0xFF303030)
@Composable
private fun CoachingOverlayRotatedPreview() {
    CompositionCoachTheme {
        CoachingOverlay(composition = previewComposition(), deviceRotationDegrees = 90, animationsEnabled = false)
    }
}
