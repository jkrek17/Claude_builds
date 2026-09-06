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
 *  - `PreviewView`'s `FILL_CENTER` scale type stretches/crops that same field of view to exactly fill the
 *    view bounds (no letterboxing, no extra crop), so "fraction of the visible frame" and "fraction of the
 *    PreviewView's own width/height" are the same number.
 *
 *  If the ViewPort were dropped, or the scale type were FIT_CENTER (which can letterbox), this would need to
 *  account for the analysis crop rect and the letterbox offset instead of a plain multiply.
 *
 * ## Device rotation (Pixel style)
 * The app stays portrait-locked, so the *preview* content (what [PreviewView] literally shows) never
 * rotates — but `:vision` now normalizes all `FrameAnalysis` geometry to physically-upright coordinates
 * (see its README), which is a *different* frame than the never-rotating preview whenever the phone is
 * actually held sideways. Every [toPx]/[toPxRect] call therefore first rotates the incoming normalized
 * point/rect from that physical-up frame onto the preview's own (unrotated, always-portrait) frame via
 * [rotatePointToDisplay]/[rotateRectToDisplay], using `deviceRotationDegrees` from
 * `com.compositioncoach.composition.model.FrameAnalysis.deviceRotationDegrees` (surfaced in
 * `CameraUiState.deviceRotationDegrees`) — *then* does the plain multiply described above. At
 * `deviceRotationDegrees == 0` (natural portrait) this rotation is the identity, so nothing changes from
 * before this fix.
 *
 * ### Deriving the physical-up -> display-up point mapping
 * Call `theta` the device's quantized physical rotation (0/90/180/270, `Surface.ROTATION_*` sense — see
 * `:vision`'s `DeviceRotationQuantizer`). The physically-upright frame is the display (preview) frame
 * further rotated by `-theta` (see `:vision`'s `UprightRotation` KDoc for that derivation, which this is
 * the mirror image of); inverting that, a point in the physical-up frame lands in the display frame after
 * rotating it by `phi = (360 - theta) % 360` using the same "rotate a unit square clockwise" formulas
 * `FrameCoordinateMapper.rotateForward` uses for pixel buffers, just with width = height = 1:
 * ```
 * phi =   0: (x, y) -> (x, y)
 * phi =  90: (x, y) -> (1 - y, x)
 * phi = 180: (x, y) -> (1 - x, 1 - y)
 * phi = 270: (x, y) -> (y, 1 - x)
 * ```
 * Checked against the task's own worked example: `theta = 90` (`ROTATION_90`) gives `phi = 270`, so the
 * physical top-left corner `(0, 0)` maps to `(0, 1)` — the display's **bottom-left** — matching the spec.
 * `theta = 180` maps `(0, 0)` (physical up-left, whatever that now points at in the real world) to
 * `(1, 1)` (display bottom-right), consistent with an upside-down hold flipping both axes. Direction
 * *vectors* (e.g. which way an arrow should point) use the same four cases but without the "1 -" offsets
 * (a pure rotation, no translation) — see [rotateVectorToDisplay].
 */
object OverlayMapper {

    /** deviceRotationDegrees=0 fast path aside, see the class KDoc for the [phi] derivation this drives. */
    private fun phiFor(deviceRotationDegrees: Int): Int {
        val theta = ((deviceRotationDegrees % 360) + 360) % 360
        return ((360 - theta) % 360 + 360) % 360
    }

    private fun rotateXY(x: Float, y: Float, phi: Int): Pair<Float, Float> = when (phi) {
        0 -> x to y
        90 -> (1f - y) to x
        180 -> (1f - x) to (1f - y)
        270 -> y to (1f - x)
        else -> error("unsupported phi=$phi (deviceRotationDegrees must be 0/90/180/270)")
    }

    private fun rotateVectorXY(dx: Float, dy: Float, phi: Int): Pair<Float, Float> = when (phi) {
        0 -> dx to dy
        90 -> -dy to dx
        180 -> -dx to -dy
        270 -> dy to -dx
        else -> error("unsupported phi=$phi (deviceRotationDegrees must be 0/90/180/270)")
    }

    /** Rotates a physical-up normalized point onto the (never-rotating) portrait preview's own frame. */
    fun rotatePointToDisplay(point: NormalizedPoint, deviceRotationDegrees: Int): NormalizedPoint {
        val (x, y) = rotateXY(point.x, point.y, phiFor(deviceRotationDegrees))
        return NormalizedPoint(x, y)
    }

    /** Rect counterpart of [rotatePointToDisplay]: rotates both corners, then re-sorts to stay axis-aligned. */
    fun rotateRectToDisplay(rect: NormalizedRect, deviceRotationDegrees: Int): NormalizedRect {
        val phi = phiFor(deviceRotationDegrees)
        val (x0, y0) = rotateXY(rect.left, rect.top, phi)
        val (x1, y1) = rotateXY(rect.right, rect.bottom, phi)
        return NormalizedRect(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))
    }

    /**
     * Rotates a *direction* (e.g. [com.compositioncoach.composition.model.Direction]'s unit dx/dy, or a
     * horizon line's tangent) rather than a point — same four cases as [rotatePointToDisplay] but with no
     * translation, since a direction has no position. This is what keeps the directional arrow / rotate
     * glyph / level indicator pointing the physically-correct way after the phone is rotated (see
     * `CompositionOverlay`'s "Arrow semantics").
     */
    fun rotateVectorToDisplay(dx: Float, dy: Float, deviceRotationDegrees: Int): Pair<Float, Float> =
        rotateVectorXY(dx, dy, phiFor(deviceRotationDegrees))

    /** [rotatePointToDisplay] followed by the plain normalized-to-pixel multiply described in the class KDoc. */
    fun toPx(point: NormalizedPoint, deviceRotationDegrees: Int, viewWidthPx: Float, viewHeightPx: Float): Pair<Float, Float> {
        val display = rotatePointToDisplay(point, deviceRotationDegrees)
        return display.x * viewWidthPx to display.y * viewHeightPx
    }

    /** [rotateRectToDisplay] followed by the plain normalized-to-pixel multiply described in the class KDoc. */
    fun toPxRect(rect: NormalizedRect, deviceRotationDegrees: Int, viewWidthPx: Float, viewHeightPx: Float): FloatRectPx {
        val display = rotateRectToDisplay(rect, deviceRotationDegrees)
        return FloatRectPx(
            left = display.left * viewWidthPx,
            top = display.top * viewHeightPx,
            right = display.right * viewWidthPx,
            bottom = display.bottom * viewHeightPx,
        )
    }
}

/** A pixel-space rectangle; avoids pulling `androidx.compose.ui.geometry.Rect` into a plain-JVM-testable path. */
data class FloatRectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}
