package com.compositioncoach.vision

import kotlin.math.roundToInt

/**
 * Integrated gyroscope rotation over an interval, in radians about the device axes (Android sensor
 * convention: x to the right of the screen, y up the screen, z out of the screen, counter-clockwise positive
 * looking down each axis toward the origin). Small-angle: the three components are integrated independently.
 */
data class AngularDelta(val x: Float, val y: Float, val z: Float) {
    companion object {
        val ZERO = AngularDelta(0f, 0f, 0f)
    }
}

/** An integer pixel shift (positive = right / down). */
data class PixelShift(val dx: Int, val dy: Int) {
    companion object {
        val NONE = PixelShift(0, 0)
    }
}

/**
 * Supplies, for a frame, how far the phone has rotated since stacking started. Implemented in `:app` on top of
 * the gyroscope (`MotionSensor.angularDeltaSince`); null means "unknown, do not shift this frame".
 */
fun interface FrameAligner {
    fun angularDeltaSinceStart(frameTimestampNs: Long): AngularDelta?
}

/**
 * Rotation-only alignment: turns a small camera rotation into the image translation it causes, for frames in
 * the display-portrait orientation (image x along device x, image y along device -y).
 *
 * For a pinhole camera with focal length `f` pixels, rotating by a small angle `theta` moves the whole image by
 * `f * theta` pixels (exact at the centre; at the edges of a ~70 degree field the error is a few percent of
 * the shift, i.e. sub-pixel for the shifts a steady hand produces). Derivation for the rear camera (looking
 * along -z): a positive rotation about +y turns the optical axis toward -x, so the scene slides right
 * (`dx = +f * wy`); a positive rotation about +x tilts it up, so the scene slides down (`dy = +f * wx`). The
 * front camera looks along +z, which flips both, and its preview is mirrored, which flips x again relative
 * to the raw image - net `dx = -f * wy`, `dy = -f * wx` in the mirrored frame. Roll about z rotates the image
 * and is *not* corrected (documented limit), nor is hand translation (parallax), which matters only for close
 * subjects.
 */
object GyroAlignment {
    /**
     * Focal length / long image edge for a typical phone main camera (~26 mm equivalent, ~69 degrees across
     * the long edge), used when the lens does not report its focal length and sensor size.
     */
    const val DEFAULT_FOCAL_TO_LONG_EDGE = 0.75f

    /** The image content shift caused by [delta], in pixels of an image whose long edge is [longEdgePx]. */
    fun contentShift(delta: AngularDelta, longEdgePx: Int, focalToLongEdge: Float, isFrontCamera: Boolean): PixelShift {
        val f = focalToLongEdge * longEdgePx
        val sign = if (isFrontCamera) -1f else 1f
        return PixelShift((sign * f * delta.y).roundToInt(), (sign * f * delta.x).roundToInt())
    }

    /**
     * Converts a shift measured in the display-portrait image into the stored buffer of an image that needs
     * [bufferToUprightDegrees] clockwise to stand upright (the inverse of [FrameCoordinateMapper]'s rotation:
     * a buffer vector (bx, by) becomes (-by, bx) at 90 degrees).
     */
    fun toBuffer(shift: PixelShift, bufferToUprightDegrees: Int): PixelShift = when (((bufferToUprightDegrees % 360) + 360) % 360) {
        90 -> PixelShift(shift.dy, -shift.dx)
        180 -> PixelShift(-shift.dx, -shift.dy)
        270 -> PixelShift(-shift.dy, shift.dx)
        else -> shift
    }

    /** Clamps both components to +/- [maxPx]. */
    fun clamp(shift: PixelShift, maxPx: Int): PixelShift =
        PixelShift(shift.dx.coerceIn(-maxPx, maxPx), shift.dy.coerceIn(-maxPx, maxPx))
}
