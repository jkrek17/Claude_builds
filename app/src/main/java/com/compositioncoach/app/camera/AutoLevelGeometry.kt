package com.compositioncoach.app.camera

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Pure geometry behind auto-level (see [AutoLevelProcessor]): straightening a photo by rotating it about its
 * centre and cropping to the largest axis-aligned rectangle with the original aspect ratio that still lies
 * entirely inside the rotated image (so no transparent/black corners are ever saved).
 *
 * ## Why only between 0.7 and 8 degrees
 * Below ~0.7 degrees a tilt is invisible to most viewers and the resampling plus a ~2% crop would cost more
 * sharpness than it buys. Above ~8 degrees the tilt is almost certainly deliberate (a Dutch angle) or the
 * sensor reading is wrong, and the crop would throw away more than a quarter of the frame. In between, a
 * tilted horizon is the single most common "amateur" tell in a photo and the stock app's own level guide
 * exists for exactly this band.
 *
 * ## The crop
 * A `w x h` rectangle centred in a `W x H` image rotated by `t` fits iff `w cos t + h sin t <= W` and
 * `w sin t + h cos t <= H` (the rectangle's corners, rotated into the image's own axes, stay inside it).
 * With `w = sW, h = sH` both reduce to `s <= 1 / (cos t + max(W/H, H/W) sin t)`, which is symmetric in W and
 * H, so the same scale applies whether the computation is done in sensor (buffer) or display orientation.
 */
object AutoLevelGeometry {

    const val MIN_LEVEL_DEGREES = 0.7f
    const val MAX_LEVEL_DEGREES = 8f

    /** Whether a shutter-time roll reading should be corrected (see the class doc for the band). */
    fun shouldLevel(rollDegrees: Float, isReliable: Boolean): Boolean {
        if (!isReliable || rollDegrees.isNaN()) return false
        val magnitude = abs(rollDegrees)
        return magnitude >= MIN_LEVEL_DEGREES && magnitude <= MAX_LEVEL_DEGREES
    }

    /** The linear scale of the largest same-aspect rectangle inside a `width x height` image rotated by [angleDegrees]. */
    fun cropScale(angleDegrees: Double, width: Int, height: Int): Double {
        require(width > 0 && height > 0)
        val t = Math.toRadians(abs(angleDegrees) % 180.0).let { if (it > Math.PI / 2) Math.PI - it else it }
        val aspect = maxOf(width.toDouble() / height, height.toDouble() / width)
        return 1.0 / (cos(t) + aspect * sin(t))
    }

    /**
     * The crop size in pixels for a `width x height` image rotated by [angleDegrees]: [cropScale] applied to
     * both sides, rounded down so the rectangle never leaves the rotated image, with the height re-derived
     * from the width so the aspect ratio stays as close to the original as integer pixels allow.
     */
    fun cropSize(width: Int, height: Int, angleDegrees: Double): Pair<Int, Int> {
        val s = cropScale(angleDegrees, width, height)
        val w = floor(width * s).toInt().coerceIn(1, width)
        val maxH = floor(height * s).toInt().coerceIn(1, height)
        val h = (w.toDouble() * height / width).toInt().coerceIn(1, maxH)
        return w to h
    }

    /**
     * EXIF orientations whose display transform includes a mirror (2 flip-horizontal, 4 flip-vertical,
     * 5 transpose, 7 transverse). Front-camera photos saved mirrored use one of these.
     */
    fun isMirroredExifOrientation(exifOrientation: Int): Boolean = exifOrientation in setOf(2, 4, 5, 7)

    /**
     * The clockwise rotation (Android `Canvas.rotate` sense, y down) to apply to the JPEG's stored pixel
     * buffer so that the *displayed* photo is levelled.
     *
     * `rollDegrees` is `DeviceOrientation.rollDegrees`: positive means the horizon appears rotated clockwise
     * in the physically-upright (and, for the front camera, mirrored) frame, which is exactly how the saved
     * photo displays once its EXIF orientation is applied. So in display space the fix is a clockwise turn of
     * `-roll`. The stored buffer differs from the display by the EXIF transform: a pure 90/180/270 rotation
     * commutes with another rotation, so the angle carries over unchanged, but a transform containing a
     * mirror reverses the sense of rotation, so the angle is negated.
     */
    fun bufferRotationDegrees(rollDegrees: Float, exifOrientation: Int): Float {
        val displayCorrection = -rollDegrees
        return if (isMirroredExifOrientation(exifOrientation)) -displayCorrection else displayCorrection
    }
}
