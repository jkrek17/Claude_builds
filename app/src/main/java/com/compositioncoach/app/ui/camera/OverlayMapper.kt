package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect

/**
 * Maps normalized (0..1) frame coordinates from [com.compositioncoach.composition.model] onto pixel
 * coordinates in the overlay `Canvas`, which is drawn at the exact size and position of the `PreviewView`.
 *
 * Why a plain multiply is correct here (and would NOT be if any of these were missing):
 *  - [com.compositioncoach.app.camera.CameraController] binds `Preview`, `ImageCapture` and `ImageAnalysis`
 *    together in one `UseCaseGroup` sharing a single `ViewPort` derived from the `PreviewView`. CameraX then
 *    crops the analysis stream to the *same field of view* the preview shows, instead of the analysis stream
 *    seeing a wider or differently-cropped sensor crop than what's on screen.
 *  - The vision pipeline normalizes all detector geometry to that upright, already-mirrored analysis frame
 *    (0,0 = top-left of what the user sees, 1,1 = bottom-right), so a normalized point already describes a
 *    fraction of the *visible* preview, not a fraction of the raw sensor image.
 *  - `PreviewView`'s `FILL_CENTER` scale type stretches/crops that same field of view to exactly fill the
 *    view bounds (no letterboxing, no extra crop), so "fraction of the visible frame" and "fraction of the
 *    PreviewView's own width/height" are the same number.
 *
 *  If the ViewPort were dropped, or the scale type were FIT_CENTER (which can letterbox), this would need to
 *  account for the analysis crop rect and the letterbox offset instead of a plain multiply.
 */
object OverlayMapper {

    fun toPx(point: NormalizedPoint, viewWidthPx: Float, viewHeightPx: Float): Pair<Float, Float> =
        point.x * viewWidthPx to point.y * viewHeightPx

    fun toPxRect(rect: NormalizedRect, viewWidthPx: Float, viewHeightPx: Float): FloatRectPx = FloatRectPx(
        left = rect.left * viewWidthPx,
        top = rect.top * viewHeightPx,
        right = rect.right * viewWidthPx,
        bottom = rect.bottom * viewHeightPx,
    )
}

/** A pixel-space rectangle; avoids pulling `androidx.compose.ui.geometry.Rect` into a plain-JVM-testable path. */
data class FloatRectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
