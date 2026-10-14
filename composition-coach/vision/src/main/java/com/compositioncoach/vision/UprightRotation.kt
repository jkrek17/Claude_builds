package com.compositioncoach.vision

/**
 * Computes the clockwise rotation (degrees, 0/90/180/270) that turns a CameraX sensor buffer *physically*
 * upright — i.e. upright as the photographer standing wherever they are actually pointing the camera would
 * see it — as opposed to `ImageProxy.imageInfo.rotationDegrees`, which only turns the buffer upright for
 * the app's *fixed* target rotation (natural portrait, since this app is `screenOrientation="portrait"` and
 * never changes `ImageAnalysis`/`Preview`'s target rotation). This is the core piece of coordinate math for
 * "analyse in physical-up coordinates": the screen itself stays portrait-locked (so the live preview content
 * never visually rotates, same as the stock Pixel Camera app), but the *analysis* — face/pose/object
 * detection, luma statistics, the segmentation mask, the horizon estimate — must reason in the scene's true
 * upright orientation, or headroom/thirds/horizon logic runs along the wrong axis whenever the phone is
 * actually held sideways.
 *
 * ### Conventions this is derived from (physical facts, not restatements of other code's comments)
 *  - **Device axes** (`SensorEvent`): +x out the screen's RIGHT edge, +y out its TOP edge, +z out of the
 *    screen face towards the viewer.
 *  - **`theta` = `deviceRotationDegrees`** ([DeviceRotationQuantizer], `Surface.ROTATION_*` sense): the
 *    phone is rotated `theta` degrees **counter-clockwise** from natural portrait as the user looking at
 *    the screen sees it. `theta = 90` (`Surface.ROTATION_90`) is therefore "right edge up".
 *  - **`R0` = `imageInfo.rotationDegrees`**: the clockwise rotation that makes the buffer upright *for the
 *    current display orientation*. For a portrait-locked activity the display orientation never changes, so
 *    `R0` is a per-camera constant (typically 90 rear, 270 front).
 *  - **Camera axes.** A camera looking along `f` with "up" `u` images the world with its own right axis
 *    `r = f x u`. The rear camera looks along **-z**, so `r = (-z) x y = +x`: device-right is image-right.
 *    The front camera looks along **+z**, so `r = z x y = -x`: device-right is image-**left**. That single
 *    handedness difference is what makes the correction's sign differ per facing (below), and it is exactly
 *    why a photo of you taken with the front camera shows your right hand on the image's left.
 *
 * ### Derivation
 * Write `D` for the display-upright image (`R0` applied to the buffer — literally what the portrait-locked
 * `PreviewView` shows for the rear camera, and its un-mirrored twin for the front camera) and `A` for the
 * physically-upright image we want to analyse.
 *
 * Take the world's "up" direction `U` and ask where it lands in `D`, in screen coordinates (x right, y
 * down). With the device rotated `theta` CCW, device `x_hat = cos(theta) * right + sin(theta) * U` and
 * `y_hat = -sin(theta) * right + cos(theta) * U`, so:
 *  - **Rear** (`r = +x`, image-up = device +y): `U` lands at `(U . x_hat, -U . y_hat) = (sin theta, -cos theta)`.
 *    That is `(0, -1)` — plain "up" — rotated **clockwise** by `theta`. So `D = rotate_cw(theta) . A`, hence
 *    `A = rotate_cw(-theta) . D = rotate_cw(R0 - theta) . buffer`.
 *  - **Front** (`r = -x`, image-up = device +y): `U` lands at `(-U . x_hat, -U . y_hat) = (-sin theta, -cos theta)`
 *    — the same magnitude the other way, i.e. rotated **counter-clockwise** by `theta`. So
 *    `D = rotate_cw(-theta) . A`, hence `A = rotate_cw(+theta) . D = rotate_cw(R0 + theta) . buffer`.
 *
 * ```
 * uprightRotationDegrees = (R0 - theta) mod 360   // rear camera
 * uprightRotationDegrees = (R0 + theta) mod 360   // front camera
 * ```
 *
 * Sanity checks on the front-camera branch, which is the one an earlier version of this file got wrong (it
 * used the rear formula for both facings, leaving the front camera's analysis frame **upside down** in both
 * landscape holds — the two formulas differ by `2 * theta = 180` degrees there, which is invisible to any
 * check that only looks at the frame's aspect ratio):
 *  - `theta = 0`: both branches collapse to `R0` — physical-up and display-up are the same thing in natural
 *    portrait, for either camera. ✓
 *  - `theta = 180`: `R0 - 180` and `R0 + 180` are the same angle mod 360, which they must be — an
 *    upside-down hold is its own opposite. ✓
 *  - `theta = 90`, front, `R0 = 270`: `(270 + 90) % 360 = 0`. Checked independently: at right-edge-up the
 *    front camera's un-mirrored display-upright image has world-up pointing to its **left** (`(-1, 0)` from
 *    the formula above), so it needs a further 90-degree *clockwise* turn to stand up, i.e. `270 + 90`. ✓
 *  - `theta = 90`, rear, `R0 = 90`: `(90 - 90) % 360 = 0` — at right-edge-up the rear sensor buffer is
 *    already physically upright as delivered, the well-known "landscape hold needs no rotation" case. ✓
 *
 * Mirroring is deliberately not part of this: [FrameCoordinateMapper] mirrors `x' = 1 - x` in the *final*
 * upright frame, which is the physical-vertical-axis mirror regardless of which rotation produced that
 * frame. `A` for the front camera is therefore `mirror(rotate_cw(R0 + theta) . buffer)` — the selfie view,
 * physically upright, with x = 0 always the left edge on screen (see `Geometry.kt` in `:composition`).
 */
object UprightRotation {

    /**
     * @param imageInfoRotationDegrees `imageProxy.imageInfo.rotationDegrees` (0/90/180/270) — the fixed
     *   display-upright rotation CameraX reports for this camera/target-rotation combination (`R0` above).
     * @param deviceRotationDegrees the phone's quantized physical rotation from natural portrait
     *   (0/90/180/270, `Surface.ROTATION_*` sense), e.g. [DeviceRotationQuantizer.currentRotation].
     * @param isFrontCamera whether the frame came from a front-facing camera. The front camera's optical
     *   axis points the opposite way, which reverses the sense in which physically rotating the phone
     *   rotates the scene inside its buffer — see the class KDoc's derivation. Mirroring is *not* applied
     *   here; [FrameCoordinateMapper] does that in the final upright frame.
     * @return the clockwise rotation (0/90/180/270) that turns the sensor buffer *physically* upright.
     */
    fun computeUprightRotationDegrees(
        imageInfoRotationDegrees: Int,
        deviceRotationDegrees: Int,
        isFrontCamera: Boolean,
    ): Int {
        val normalizedR0 = ((imageInfoRotationDegrees % 360) + 360) % 360
        val normalizedTheta = ((deviceRotationDegrees % 360) + 360) % 360
        val signedTheta = if (isFrontCamera) normalizedTheta else -normalizedTheta
        return ((normalizedR0 + signedTheta) % 360 + 360) % 360
    }
}
