package com.compositioncoach.vision

import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect

/**
 * All rotation / crop / mirror math for turning camera-buffer-space coordinates into the upright,
 * crop-relative, front-camera-mirrored [NormalizedPoint] / [NormalizedRect] space that the rest of
 * the app expects (see `Geometry.kt` in `:composition` for the coordinate contract).
 *
 * Two related coordinate spaces show up while building a [com.compositioncoach.composition.model.FrameAnalysis]
 * and this class bridges both of them to normalized coordinates:
 *
 *  1. **Sensor space** — raw pixel coordinates in the buffer as CameraX/the [android.media.Image]
 *     delivers it, *before* any rotation. `imageProxy.cropRect` and our own [ImageStatisticsComputer]
 *     luma sampling both work in this space. Use [sensorPointToNormalized] / [sensorRectToNormalized],
 *     and [normalizedPointToSensor] for the inverse (used to backward-sample the luma plane).
 *  2. **Rotated-full space** — ML Kit rotates the buffer internally (we pass `rotationDegrees` into
 *     `InputImage.fromMediaImage`) and reports detection boxes/landmarks already rotated, in pixel
 *     coordinates of the *rotated, uncropped* buffer (width/height swapped from sensor space when
 *     rotation is 90 or 270 — this is a documented ML Kit quirk). Use [rotatedFullPointToNormalized]
 *     / [rotatedFullRectToNormalized] for those.
 *
 * Both paths converge on the same final step: subtract the (rotated) crop-rect origin, divide by the
 * (rotated) crop-rect size to land in 0..1 *of the crop* (not the full buffer — CameraX's ViewPort
 * means the analysis crop matches the preview's field of view, so this is what the user actually
 * sees), then mirror `x' = 1 - x` for the front camera so x=0 is always the left edge on screen.
 *
 * `rotationDegrees` follows the CameraX/ML Kit convention: the clockwise rotation needed to turn the
 * sensor buffer upright as the user sees the preview. Only 0/90/180/270 are supported (CameraX never
 * reports anything else). All point/rect coordinates here are continuous pixel coordinates (i.e. the
 * range is `[0, width]` / `[0, height]`, not `[0, width-1]`), matching both ML Kit's float boxes and
 * `Rect`'s left/top/right/bottom edge convention.
 *
 * ### Rotation derivation
 * For a buffer of size (w, h) rotated clockwise by `rotationDegrees` to become upright, a continuous
 * point (x, y) maps to rotated-space (rx, ry) as:
 *  - 0°:   (rx, ry) = (x, y)                  — output size (w, h)
 *  - 90°:  (rx, ry) = (h - y, x)               — output size (h, w)
 *  - 180°: (rx, ry) = (w - x, h - y)           — output size (w, h)
 *  - 270°: (rx, ry) = (y, w - x)               — output size (h, w)
 *
 * These can be checked against the buffer's four corners: e.g. at 90°, sensor top-left (0,0) maps to
 * (h, 0) — the top-right corner of the (h × w) rotated buffer — which is exactly where the top-left
 * corner of a photo ends up after physically rotating it 90° clockwise.
 */
class FrameCoordinateMapper(
    val sensorWidth: Int,
    val sensorHeight: Int,
    val rotationDegrees: Int,
    cropLeft: Int,
    cropTop: Int,
    cropRight: Int,
    cropBottom: Int,
    val mirror: Boolean,
) {
    init {
        require(rotationDegrees == 0 || rotationDegrees == 90 || rotationDegrees == 180 || rotationDegrees == 270) {
            "unsupported rotationDegrees=$rotationDegrees (must be 0/90/180/270)"
        }
    }

    /** Size of the buffer once rotated upright (swapped from sensor space at 90/270). */
    val rotatedFullWidth: Int = if (rotationDegrees == 90 || rotationDegrees == 270) sensorHeight else sensorWidth
    val rotatedFullHeight: Int = if (rotationDegrees == 90 || rotationDegrees == 270) sensorWidth else sensorHeight

    // The crop rect, rotated into the same space as rotatedFullWidth/Height, min/max-sorted.
    private val cropRotatedLeft: Float
    private val cropRotatedTop: Float
    private val cropRotatedRight: Float
    private val cropRotatedBottom: Float

    init {
        val c0 = rotateForward(cropLeft.toFloat(), cropTop.toFloat())
        val c1 = rotateForward(cropRight.toFloat(), cropBottom.toFloat())
        cropRotatedLeft = minOf(c0.first, c1.first)
        cropRotatedRight = maxOf(c0.first, c1.first)
        cropRotatedTop = minOf(c0.second, c1.second)
        cropRotatedBottom = maxOf(c0.second, c1.second)
    }

    private val cropRotatedWidth: Float = (cropRotatedRight - cropRotatedLeft).takeIf { it > 0f } ?: 1f
    private val cropRotatedHeight: Float = (cropRotatedBottom - cropRotatedTop).takeIf { it > 0f } ?: 1f

    /** Pixel width/height of the crop rect once rotated upright; this is the "frame" the app sees. */
    val outputWidth: Int get() = cropRotatedWidth.toInt().coerceAtLeast(1)
    val outputHeight: Int get() = cropRotatedHeight.toInt().coerceAtLeast(1)

    /** Rotate a continuous point from sensor space into rotated-full space (no crop, no mirror). */
    private fun rotateForward(x: Float, y: Float): Pair<Float, Float> = when (rotationDegrees) {
        0 -> x to y
        90 -> (sensorHeight - y) to x
        180 -> (sensorWidth - x) to (sensorHeight - y)
        270 -> y to (sensorWidth - x)
        else -> error("unreachable")
    }

    /** Inverse of [rotateForward]: rotated-full space back to sensor space. */
    private fun rotateInverse(rx: Float, ry: Float): Pair<Float, Float> = when (rotationDegrees) {
        0 -> rx to ry
        90 -> ry to (sensorHeight - rx)
        180 -> (sensorWidth - rx) to (sensorHeight - ry)
        270 -> (sensorWidth - ry) to rx
        else -> error("unreachable")
    }

    private fun rotatedFullToNormalized(rx: Float, ry: Float): Pair<Float, Float> {
        var nx = (rx - cropRotatedLeft) / cropRotatedWidth
        val ny = (ry - cropRotatedTop) / cropRotatedHeight
        if (mirror) nx = 1f - nx
        return nx to ny
    }

    private fun normalizedToRotatedFull(nx: Float, ny: Float): Pair<Float, Float> {
        val x = if (mirror) 1f - nx else nx
        val rx = cropRotatedLeft + x * cropRotatedWidth
        val ry = cropRotatedTop + ny * cropRotatedHeight
        return rx to ry
    }

    /** Map a point in sensor pixel space (e.g. our own luma sampling) to normalized upright/crop/mirror space. */
    fun sensorPointToNormalized(x: Float, y: Float): NormalizedPoint {
        val (rx, ry) = rotateForward(x, y)
        val (nx, ny) = rotatedFullToNormalized(rx, ry)
        return NormalizedPoint(nx, ny)
    }

    /** Inverse of [sensorPointToNormalized]: normalized (upright, crop-relative, mirrored) back to sensor pixels. */
    fun normalizedPointToSensor(nx: Float, ny: Float): Pair<Float, Float> {
        val (rx, ry) = normalizedToRotatedFull(nx, ny)
        return rotateInverse(rx, ry)
    }

    /** Map an axis-aligned rect in sensor space to normalized space. */
    fun sensorRectToNormalized(left: Float, top: Float, right: Float, bottom: Float): NormalizedRect {
        val p0 = sensorPointToNormalized(left, top)
        val p1 = sensorPointToNormalized(right, bottom)
        return NormalizedRect(minOf(p0.x, p1.x), minOf(p0.y, p1.y), maxOf(p0.x, p1.x), maxOf(p0.y, p1.y))
    }

    /** Map a point already in ML Kit's rotated-full pixel space (no further rotation needed). */
    fun rotatedFullPointToNormalized(x: Float, y: Float): NormalizedPoint {
        val (nx, ny) = rotatedFullToNormalized(x, y)
        return NormalizedPoint(nx, ny)
    }

    /** Map a rect already in ML Kit's rotated-full pixel space (e.g. `Face.boundingBox`) to normalized space. */
    fun rotatedFullRectToNormalized(left: Float, top: Float, right: Float, bottom: Float): NormalizedRect {
        val p0 = rotatedFullPointToNormalized(left, top)
        val p1 = rotatedFullPointToNormalized(right, bottom)
        return NormalizedRect(minOf(p0.x, p1.x), minOf(p0.y, p1.y), maxOf(p0.x, p1.x), maxOf(p0.y, p1.y))
    }
}
