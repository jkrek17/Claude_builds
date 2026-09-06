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
 */
@Composable
fun DebugGeometryOverlay(composition: SmoothedComposition, debugFrame: DebugFrameData, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        debugFrame.subjectMask?.let { drawMaskHeat(it) }
        debugFrame.objects.forEach { drawObjectBox(it) }
        composition.raw.subjects.forEach { subject -> drawSubject(subject) }
    }
}

/** Foreground-probability heat: each mask cell tinted green, alpha proportional to probability, capped low. */
private fun DrawScope.drawMaskHeat(mask: SubjectMask) {
    val cellWidth = size.width / mask.gridWidth
    val cellHeight = size.height / mask.gridHeight
    for (row in 0 until mask.gridHeight) {
        for (col in 0 until mask.gridWidth) {
            val probability = mask.at(col, row)
            if (probability <= 0f) continue
            drawRect(
                color = Color(0xFF34D399).copy(alpha = (probability * MASK_MAX_ALPHA).coerceAtMost(MASK_MAX_ALPHA)),
                topLeft = Offset(col * cellWidth, row * cellHeight),
                size = androidx.compose.ui.geometry.Size(cellWidth, cellHeight),
            )
        }
    }
}

private fun DrawScope.drawObjectBox(obj: DetectedObject) {
    drawRectOutline(obj.bounds, OBJECT_BOX_COLOR, 1.5.dp.toPx(), dashed = true)
    val label = "${obj.category.name.lowercase()} ${(obj.confidence * 100).toInt()}%"
    drawContext.canvas.nativeCanvas.drawText(
        label,
        obj.bounds.left * size.width,
        (obj.bounds.top * size.height - 4.dp.toPx()).coerceAtLeast(10.dp.toPx()),
        android.graphics.Paint().apply {
            color = android.graphics.Color.argb(230, 127, 216, 255)
            textSize = 10.sp.toPx()
            isAntiAlias = true
        },
    )
}

private fun DrawScope.drawSubject(subject: DetectedSubject) {
    val color = if (subject.isPrimary) Color(0xFF34D399) else Color(0xFFFFC857).copy(alpha = 0.7f)
    drawRectOutline(subject.bounds, color, if (subject.isPrimary) 2.5.dp.toPx() else 1.5.dp.toPx())

    subject.face?.let { face ->
        face.leftEye?.let { drawDot(it, color) }
        face.rightEye?.let { drawDot(it, color) }
        face.noseBase?.let { drawDot(it, color) }
    }
    subject.body?.landmarks?.values?.forEach { landmark ->
        if (landmark.inFrameLikelihood >= 0.5f) drawDot(landmark.position, color.copy(alpha = 0.8f))
    }
}

private fun DrawScope.drawRectOutline(rect: NormalizedRect, color: Color, strokeWidth: Float, dashed: Boolean = false) {
    val topLeft = Offset(rect.left * size.width, rect.top * size.height)
    val rectSize = androidx.compose.ui.geometry.Size((rect.width) * size.width, (rect.height) * size.height)
    drawRect(
        color = color,
        topLeft = topLeft,
        size = rectSize,
        style = Stroke(width = strokeWidth, pathEffect = if (dashed) OBJECT_DASH else null),
    )
}

private fun DrawScope.drawDot(point: com.compositioncoach.composition.model.NormalizedPoint, color: Color) {
    drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(point.x * size.width, point.y * size.height))
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
