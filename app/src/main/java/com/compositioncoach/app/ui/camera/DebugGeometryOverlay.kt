package com.compositioncoach.app.ui.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.compositioncoach.composition.model.DetectedSubject
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SmoothedComposition

/**
 * Debug-only geometry: every detected subject's box (the primary subject highlighted), its face/body
 * landmarks. Only mounted when debug mode is on — [CompositionOverlay] never draws boxes or regions.
 */
@Composable
fun DebugGeometryOverlay(composition: SmoothedComposition, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        composition.raw.subjects.forEach { subject ->
            drawSubject(subject)
        }
    }
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

private fun DrawScope.drawRectOutline(rect: NormalizedRect, color: Color, strokeWidth: Float) {
    val topLeft = Offset(rect.left * size.width, rect.top * size.height)
    val rectSize = androidx.compose.ui.geometry.Size((rect.width) * size.width, (rect.height) * size.height)
    drawRect(color = color, topLeft = topLeft, size = rectSize, style = Stroke(width = strokeWidth))
}

private fun DrawScope.drawDot(point: com.compositioncoach.composition.model.NormalizedPoint, color: Color) {
    drawCircle(color = color, radius = 3.dp.toPx(), center = Offset(point.x * size.width, point.y * size.height))
}
