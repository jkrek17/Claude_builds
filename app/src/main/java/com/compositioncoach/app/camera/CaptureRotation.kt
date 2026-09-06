package com.compositioncoach.app.camera

import android.view.Surface

/**
 * The one pure function behind [CameraController.setCaptureRotationDegrees]: which `Surface.ROTATION_*`
 * constant to hand `ImageCapture.targetRotation` so the JPEG CameraX saves is upright in the gallery,
 * given the phone's quantized *physical* rotation from natural portrait.
 *
 * ### Why this is not `previewView.display.rotation`
 * The activity is `screenOrientation="portrait"`, so the display rotation it sees never changes no matter
 * how the phone is held — using it would EXIF-tag every photo as portrait, which is what makes
 * landscape-held shots come out sideways in the gallery. The physical rotation comes from `:vision`'s
 * gravity-derived `DeviceRotationQuantizer` instead (`FrameAnalysis.deviceRotationDegrees`).
 *
 * ### The mapping is the identity, and that is a derivation, not a coincidence
 * `Surface.ROTATION_90` is *defined* as "the device is rotated 90 degrees counter-clockwise from its
 * natural orientation" — right edge up. `DeviceRotationQuantizer` emits its bands in exactly that sense
 * (its input is `gravityToRollDegrees`, which reads `+90` when up in device axes is `(+g, 0, 0)`, i.e.
 * right edge up). Same quantity, same sense, so:
 *
 * ```
 * hold             deviceRotationDegrees   ImageCapture.targetRotation
 * portrait                             0   Surface.ROTATION_0     (= 0)
 * right edge up                       90   Surface.ROTATION_90    (= 1)
 * upside down                        180   Surface.ROTATION_180   (= 2)
 * left edge up                       270   Surface.ROTATION_270   (= 3)
 * ```
 *
 * This is the same table Android's own `OrientationEventListener` snippet for CameraX produces, reached
 * from the other side: that listener reports degrees *clockwise* from natural (its 90 means "left side at
 * the top") and therefore has to map `45..135 -> ROTATION_270`, the mirror of this table. Ours is already
 * counter-clockwise-positive, so no flip is needed — worth stating explicitly, because silently reusing
 * that snippet's `when` block against a counter-clockwise input is precisely how a 90/270 swap gets
 * shipped.
 *
 * Anything not a multiple of 90 (or an unexpected value) falls back to `Surface.ROTATION_0`, matching the
 * quantizer's own "freeze on the last known good band" conservatism.
 */
object CaptureRotation {

    /** @param deviceRotationDegrees the quantized physical rotation from natural portrait (0/90/180/270). */
    fun surfaceRotationFor(deviceRotationDegrees: Int): Int =
        when (((deviceRotationDegrees % 360) + 360) % 360) {
            90 -> Surface.ROTATION_90
            180 -> Surface.ROTATION_180
            270 -> Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
}
