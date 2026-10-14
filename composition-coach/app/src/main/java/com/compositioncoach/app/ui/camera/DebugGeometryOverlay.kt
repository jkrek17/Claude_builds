package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.compositioncoach.app.ui.theme.CompositionCoachTheme
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.DetectedObject
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.ObjectCategory
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.composition.model.SubjectKind
import com.compositioncoach.composition.model.SubjectMask

private val OBJECT_BOX_COLOR = Color(0xFF7FD8FF)
private val OBJECT_DASH = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
private const val MASK_MAX_ALPHA = 0.35f

/**
 * Debug-only geometry: every detected subject's box (the primary subject highlighted) with face/body
 * landmarks, every raw prominent object from the object detector (dashed, category + confidence — these
 * may be a superset of the boxes that actually became a subject, see `:vision`'s `ObjectMapper`), and the
 * subject mask as a faint heat layer drawn *behind* everything else. Only mounted when debug mode is on —
 * [CompositionOverlay] never draws boxes, regions or the mask, debug or not.
 *
 * Like [CompositionOverlay], everything here is `FrameAnalysis`'s physical-up coordinates; every
 * point/rect drawn goes through [OverlayMapper] with [deviceRotationDegrees] so debug geometry lines up
 * with the live overlay above it regardless of how the phone is physically held.
 */
@Composable
fun DebugGeometryOverlay(
    composition: SmoothedComposition,
    debugFrame: DebugFrameData,
    deviceRotationDegrees: Int = 0,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        debugFrame.subjectMask?.let { drawMaskHeat(it, deviceRotationDegrees) }
        debugFrame.objects.forEach { drawObjectBox(it, deviceRotationDegrees) }
        composition.raw.subjects.forEach { subject -> drawSubject(subject, deviceRotationDegrees) }
    }
}

/**
 * Foreground-probability heat: each mask cell tinted green, alpha proportional to probability, capped
 * low. Each cell's own normalized rect (not just its center) is rotated via [OverlayMapper.toPxRect] —
 * rotation by a multiple of 90 degrees keeps the cell axis-aligned, just possibly with width/height
 * swapped, which `toPxRect` already accounts for.
 */
private fun DrawScope.drawMaskHeat(mask: SubjectMask, deviceRotationDegrees: Int) {
    val cellW = 1f / mask.gridWidth
    val cellH = 1f / mask.gridHeight
    for (row in 0 until mask.gridHeight) {
        for (col in 0 until mask.gridWidth) {
            val probability = mask.at(col, row)
            if (probability <= 0f) continue
            val cellRect = NormalizedRect(col * cellW, row * cellH, (col + 1) * cellW, (row + 1) * cellH)
            val px = OverlayMapper.toPxRect(cellRect, deviceRotationDegrees, size.width, size.height)
            drawRect(
                color = Color(0xFF34D399).copy(alpha = (probability * MASK_MAX_ALPHA).coerceAtMost(MASK_MAX_ALPHA)),
                topLeft = Offset(px.left, px.top),
                size = androidx.compose.ui.geometry.Size(px.width, px.height),
            )
        }
    }
}

private fun DrawScope.drawObjectBox(obj: DetectedObject, deviceRotationDegrees: Int) {
    val px = drawRectOutline(obj.bounds, OBJECT_BOX_COLOR, 1.5.dp.toPx(), deviceRotationDegrees, dashed = true)
    val label = "${obj.category.name.lowercase()} ${(obj.confidence * 100).toInt()}%"
    drawContext.canvas.nativeCanvas.drawText(
        label,
        minOf(px.left, px.right),
        (minOf(px.top, px.bottom) - 4.dp.toPx()).coerceAtLeast(10.dp.toPx()),
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(230, 127, 216, 255)
            textSize = 10.sp.toPx()
            isAntiAlias = true
        },
    )
}

private fun DrawScope.drawSubject(subject: DetectedSubject, deviceRotationDegrees: Int) {
    val color = if (subject.isPrimary) Color(0xFF34D399) else Color(0xFFFFC857).copy(alpha = 0.7f)
    drawRectOutline(subject.bounds, color, if (subject.isPrimary) 2.5.dp.toPx() else 1.5.dp.toPx(), deviceRotationDegrees)

    subject.face?.let { face ->
        face.leftEye?.let { drawDot(it, color, deviceRotationDegrees) }
        face.rightEye?.let { drawDot(it, color, deviceRotationDegrees) }
        face.noseBase?.let { drawDot(it, color, deviceRotationDegrees) }
    }
    subject.body?.landmarks?.values?.forEach { landmark ->
        if (landmark.inFrameLikelihood >= 0.5f) drawDot(landmark.position, color.copy(alpha = 0.8f), deviceRotationDegrees)
    }
}

/** @return the mapped pixel rect, so callers (e.g. [drawObjectBox]'s label) can anchor off it too. */
private fun DrawScope.drawRectOutline(
    rect: NormalizedRect,
    color: Color,
    strokeWidth: Float,
    deviceRotationDegrees: Int,
    dashed: Boolean = false,
): FloatRectPx {
    val px = OverlayMapper.toPxRect(rect, deviceRotationDegrees, size.width, size.height)
    drawRect(
        color = color,
        topLeft = Offset(px.left, px.top),
        size = androidx.compose.ui.geometry.Size(px.width, px.height),
        style = Stroke(width = strokeWidth, pathEffect = if (dashed) OBJECT_DASH else null),
    )
    return px
}

private fun DrawScope.drawDot(point: com.compositioncoach.composition.model.NormalizedPoint, color: Color, deviceRotationDegrees: Int) {
    val (x, y) = OverlayMapper.toPx(point, deviceRotationDegrees, size.width, size.height)
    drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(x, y))
}

@Preview(name = "Object box + mask heat", showBackground = true, backgroundColor = 0xFF202020)
@Composable
private fun DebugGeometryOverlayPreview() {
    val maskGrid = 8
    val mask = SubjectMask(
        gridWidth = maskGrid,
        gridHeight = maskGrid,
        probability = FloatArray(maskGrid * maskGrid) { i ->
            val col = i % maskGrid; val row = i / maskGrid
            if (col in 2..5 && row in 1..6) 0.9f else 0.05f
        },
    )
    val objectSubject = DetectedSubject(
        id = 1,
        kind = SubjectKind.OBJECT,
        bounds = NormalizedRect(0.55f, 0.5f, 0.85f, 0.8f),
        isPrimary = true,
    )
    CompositionCoachTheme {
        DebugGeometryOverlay(
            composition = SmoothedComposition(
                displayScore = 70,
                activeRecommendations = emptyList(),
                isShootReady = false,
                scene = CompositionResult.empty().scene,
                primarySubject = objectSubject,
                raw = CompositionResult.empty().copy(subjects = listOf(objectSubject), primarySubject = objectSubject),
            ),
            debugFrame = DebugFrameData(
                objects = listOf(
                    DetectedObject(id = 1, bounds = NormalizedRect(0.55f, 0.5f, 0.85f, 0.8f), category = ObjectCategory.FOOD, confidence = 0.82f),
                ),
                subjectMask = mask,
            ),
        )
    }
}
