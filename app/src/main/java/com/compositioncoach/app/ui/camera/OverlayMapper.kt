package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import kotlin.math.atan2

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
 * rotates — but `:vision` normalizes all `FrameAnalysis` geometry to physically-upright coordinates (see
 * its README), which is a *different* frame than the never-rotating preview whenever the phone is actually
 * held sideways. Every [toPx]/[toPxRect] call therefore first rotates the incoming normalized point/rect
 * from that physical-up frame onto the preview's own (unrotated, always-portrait) frame via
 * [rotatePointToDisplay]/[rotateRectToDisplay], using `deviceRotationDegrees` from
 * `com.compositioncoach.composition.model.FrameAnalysis.deviceRotationDegrees` (surfaced in
 * `CameraUiState.deviceRotationDegrees`) — *then* does the plain multiply described above. At
 * `deviceRotationDegrees == 0` (natural portrait) this rotation is the identity.
 *
 * ### Deriving the physical-up -> display mapping (this is the sign two previous attempts got wrong)
 * Call `theta` the device's quantized physical rotation (0/90/180/270, `Surface.ROTATION_*` sense: the
 * phone is turned `theta` degrees **counter-clockwise** from natural portrait, so `theta = 90` is
 * "right edge up"). Screen coordinates are x right, y down; a *visually clockwise* rotation by `phi` in
 * that (y-down) space is the matrix `[[cos phi, -sin phi], [sin phi, cos phi]]`.
 *
 * Ground truth is gravity, not intuition. With the device rotated `theta` CCW, its axes in world terms are
 * `x_hat = cos(theta) * right + sin(theta) * up` and `y_hat = -sin(theta) * right + cos(theta) * up`. The
 * portrait-locked preview draws the scene in device axes, so:
 *  - world **up** appears on screen at `(up . x_hat, -up . y_hat) = (sin theta, -cos theta)`
 *  - world **right** appears on screen at `(right . x_hat, -right . y_hat) = (cos theta, sin theta)`
 *
 * Both are exactly `(0, -1)` and `(1, 0)` rotated **clockwise by theta**. The analysis frame is the frame
 * in which up is `(0, -1)` and right is `(1, 0)` by construction, so:
 * ```
 * display = rotate_clockwise(theta) . physicalUp        // phi == theta, NOT 360 - theta
 * ```
 * Checked against the two field reports this fixes, both at `theta = 90` (right edge up):
 *  - "Move slightly right" is the physical-right vector `(1, 0)`; `phi = 90` sends it to `(0, +1)` —
 *    the screen's **bottom**, which is where physical right actually is in that hold. The shipped build
 *    used `phi = 360 - theta = 270`, which sent it to `(0, -1)`, the screen's top — exactly the wrong
 *    arrow the tester photographed. (`phi` and `360 - phi` differ by 180 degrees at 90 and 270, so every
 *    overlay point was also point-reflected through the frame's centre in both landscape holds, which is
 *    the rest of "rotation doesn't work". They agree at 0 and 180, which is why portrait always looked
 *    right.)
 *  - The physical **top-centre** of the scene, `(0.5, 0)`, lands at `(1, 0.5)` — the screen's
 *    right-centre — matching where the preview visibly shows the top of the scene at that hold.
 *
 * Points use the same four cases as `FrameCoordinateMapper.rotateForward` with width = height = 1;
 * direction *vectors* use them without the `1 -` offsets (a pure rotation, no translation) —
 * see [rotateVectorToDisplay].
 * ```
 * phi =   0: (x, y) -> (x, y)          (dx, dy) -> ( dx,  dy)
 * phi =  90: (x, y) -> (1 - y, x)      (dx, dy) -> (-dy,  dx)
 * phi = 180: (x, y) -> (1 - x, 1 - y)  (dx, dy) -> (-dx, -dy)
 * phi = 270: (x, y) -> (y, 1 - x)      (dx, dy) -> ( dy, -dx)
 * ```
 */
object OverlayMapper {

    /**
     * The clockwise angle that carries the physical-up frame onto the display frame. It is
     * [deviceRotationDegrees] itself — see the class KDoc's gravity-anchored derivation. (It is *not*
     * `360 - deviceRotationDegrees`; that inversion is the bug this file's history is about.)
     */
    private fun phiFor(deviceRotationDegrees: Int): Int =
        ((deviceRotationDegrees % 360) + 360) % 360

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

    /**
     * The Compose rotation (`Modifier.rotate`/`graphicsLayer { rotationZ = ... }` degrees,
     * **clockwise-positive**) that keeps Pixel-style chrome — icons, the score badge, the guidance banner
     * text, all drawn via [RotatedChrome] — upright *to the person holding the phone*, for every
     * `Surface.ROTATION_*` [deviceRotationDegrees].
     *
     * ### Derivation
     * A glyph drawn with no rotation has its own "up" pointing along `(0, -1)` in screen coordinates;
     * rotated clockwise by `phi` its up points along `(sin phi, -cos phi)`. For the glyph to look upright
     * to the photographer, its up must point wherever *physical* up currently appears on the screen, which
     * is [rotateVectorToDisplay] of `(0, -1)` — call it `v`. Solving `(sin phi, -cos phi) = v` gives
     * ```
     * phi = atan2(v.x, -v.y)
     * ```
     * Since `v = (sin theta, -cos theta)` (class KDoc), this reduces to `phi = theta`: the phone turned
     * `theta` counter-clockwise needs chrome turned `theta` clockwise to cancel it. `atan2` reports in
     * `(-180, 180]`, so `theta = 270` comes back as `-90` — the same angle, and the shorter way round for
     * [rememberControlCounterRotation]'s animation.
     *
     * ### Why this is spelled out rather than written as `theta.toFloat()`
     * The value is deliberately routed through [rotateVectorToDisplay] so chrome and the directional arrow
     * can never drift apart again. Note the two are *not* the same expression: the previous shipped build
     * paired an inverted [rotateVectorToDisplay] with `atan2(-v.x, -v.y)` here, and the two sign errors
     * cancelled — chrome came out right while every arrow and box was 180 degrees out. Fixing only the
     * obvious one flips chrome back to the original `-theta` field bug ("Move slightly right" reading
     * bottom-to-top at `ROTATION_90`), so both are pinned independently by tests: `RotationTruthTableTest`
     * derives each from gravity, and `RotatedChromeRotationTest` checks the sign that actually reaches the
     * screen.
     *
     * ### Checked against the field report (`ROTATION_90`, phone's right edge pointing up)
     * `v = rotateVectorToDisplay(0, -1, 90) = (1, 0)` — physical up appears at the screen's right, which is
     * where the preview visibly shows the top of the scene in that hold. `phi = atan2(1, 0) = +90`: chrome
     * rotates 90 degrees *clockwise*, so "Move slightly right" reads top-to-bottom with its own up toward
     * the screen's right edge — which is up in the real world for that hold.
     */
    fun uprightChromeAngleDegrees(deviceRotationDegrees: Int): Float {
        val (vx, vy) = rotateVectorToDisplay(0f, -1f, deviceRotationDegrees)
        return Math.toDegrees(atan2(vx, -vy).toDouble()).toFloat()
    }

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
