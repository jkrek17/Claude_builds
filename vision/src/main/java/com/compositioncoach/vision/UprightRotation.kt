package com.compositioncoach.vision

/**
 * Computes the clockwise rotation (degrees, 0/90/180/270) that turns a CameraX sensor buffer *physically*
 * upright — i.e. upright as the photographer standing wherever they are actually pointing the camera would
 * see it — as opposed to [android.media.ImageProxy.getImageInfo]'s own `rotationDegrees`, which only turns
 * the buffer upright for the app's *fixed* target rotation (natural portrait, since this app is
 * `screenOrientation="portrait"` and never changes `ImageAnalysis`/`Preview`'s target rotation). This is
 * the "Pixel style" fix's core piece of coordinate math: the screen itself stays portrait-locked (so the
 * live preview content never visually rotates, same as the stock Pixel Camera app), but the *analysis* —
 * face/pose/object detection, luma statistics, the segmentation mask, the horizon estimate — must reason
 * in the scene's true upright orientation, or headroom/thirds/horizon logic runs along the wrong axis
 * whenever the phone is actually held sideways.
 *
 * ### Derivation
 * Call `R0` the buffer's fixed display-upright rotation ([android.media.ImageProxy.getImageInfo]'s
 * `rotationDegrees` — constant for a given camera facing on a given device, since the app never changes
 * its target rotation) and `theta` the phone's current *physical* rotation from natural portrait, in the
 * same sense as [DeviceRotationQuantizer]'s output and Android's `Surface.ROTATION_*` (0/90/180/270 =
 * degrees the phone has been turned counter-clockwise from natural portrait — `Surface.ROTATION_90` means
 * the phone's top edge now points left).
 *
 * At `theta = 0` (natural portrait) physical-up and display-up are the same thing, so the answer is `R0`
 * unchanged. As the phone is physically rotated further by `theta` counter-clockwise, the *whole camera
 * assembly* rotates with it, which — for a fixed external scene — rotates the raw sensor image an
 * additional `theta` **clockwise** relative to what it would have captured at `theta = 0` (this is the
 * same "spinning camera" fact [OrientationSensor]'s own roll-sign derivation relies on: rotating the
 * camera CCW by `theta` makes a fixed scene sweep CW by `theta` within the frame it captures). To undo
 * that extra bake-in and land back on physical-up, the correction must subtract `theta` from the
 * already-known `R0`:
 * ```
 * uprightRotationDegrees = (R0 - theta + 360) % 360
 * ```
 * i.e. `k = (360 - theta) % 360` in `(imageInfo.rotationDegrees + k) % 360`.
 *
 * ### Checked against all four device rotations (rear camera, R0 = 90 — the common Pixel-style mount)
 *  - `theta = 0`   (natural portrait): `(90 - 0) % 360 = 90` — unchanged; matches the well-known
 *    "rear camera, portrait natural: rotationDegrees 90 -> upright" baseline.
 *  - `theta = 90`  (`Surface.ROTATION_90`, top of phone pointing left — physically landscape):
 *    `(90 - 90) % 360 = 0` — the sensor image is *already* upright once rotated by 0, matching the task
 *    spec's own worked example for this exact case.
 *  - `theta = 180` (upside down): `(90 - 180 + 360) % 360 = 270`.
 *  - `theta = 270` (`Surface.ROTATION_270`, top of phone pointing right): `(90 - 270 + 360) % 360 = 180`.
 *
 * The same formula holds for the front camera unmodified — physically rotating the phone rotates both
 * cameras identically, so only `R0` itself differs (front cameras are commonly mounted 180 degrees
 * opposite the rear one, e.g. `R0 = 270`), not the correction applied on top of it. Front-camera mirroring
 * is unaffected by any of this: [FrameCoordinateMapper] always mirrors `x' = 1 - x` in the *final* upright
 * frame, which is the physical-vertical-axis mirror regardless of which `rotationDegrees` produced that
 * frame.
 */
object UprightRotation {

    /**
     * @param imageInfoRotationDegrees `imageProxy.imageInfo.rotationDegrees` (0/90/180/270) — the fixed
     *   display-upright rotation CameraX reports for this camera/target-rotation combination.
     * @param deviceRotationDegrees the phone's quantized physical rotation from natural portrait
     *   (0/90/180/270), e.g. [DeviceRotationQuantizer.currentRotation].
     * @return the clockwise rotation (0/90/180/270) that turns the sensor buffer *physically* upright.
     */
    fun computeUprightRotationDegrees(imageInfoRotationDegrees: Int, deviceRotationDegrees: Int): Int {
        val normalizedR0 = ((imageInfoRotationDegrees % 360) + 360) % 360
        val normalizedTheta = ((deviceRotationDegrees % 360) + 360) % 360
        return ((normalizedR0 - normalizedTheta) % 360 + 360) % 360
    }
}
