package com.compositioncoach.vision

import kotlin.math.abs

/**
 * Quantizes a continuously-varying roll angle (see [OrientationSensor]'s sign convention: positive =
 * horizon appears clockwise on screen) into one of Android's four `Surface.ROTATION_*` bands, in
 * degrees (0/90/180/270), representing how far the phone is physically rotated from natural portrait.
 * This is the "Pixel style" fix's device-rotation source: the app stays portrait-locked, so
 * `Display.getRotation()` never changes, but [OrientationSensor]'s own gravity-derived roll already
 * *is* the phone's true physical rotation (see that class's KDoc) — this just buckets the continuous
 * value into four stable bands instead of a jittery float.
 *
 * Two guards keep this from flapping near a 45-degree boundary while someone is mid-rotation:
 *  - **±25-degree hysteresis** ([HYSTERESIS_DEGREES]): once settled on a band, the roll must swing past
 *    that band's nominal edge by this much before a *different* band is even considered a candidate —
 *    a phone resting right at ~45 degrees of roll doesn't ping-pong between two bands on sensor noise
 *    alone. Concretely: the current band stays "sticky" out to 45+25 = 70 degrees from its own centre;
 *    only once the roll is further than that from the current band's centre does the nominal (un-
 *    hysteresis'd) band of the raw angle become the new candidate.
 *  - **400ms debounce** ([DEBOUNCE_MS]): a new candidate band must be the same candidate continuously
 *    for this long before it is actually committed — a fast snap-rotation settles once the hand stops
 *    moving, rather than emitting every intermediate band it swept through on the way.
 *
 * Roll readings tagged unreliable (see [com.compositioncoach.composition.model.DeviceOrientation.isReliable]
 * — phone pointed near straight up/down, where roll is numerically undefined) are ignored entirely: this
 * quantizer freezes on the last committed band rather than guessing from a numerically unstable roll.
 */
class DeviceRotationQuantizer {

    @Volatile
    var currentRotation: Int = 0
        private set

    private var pendingRotation: Int = 0
    private var pendingSinceMs: Long = 0L
    private var pendingArmed: Boolean = false

    /**
     * Feeds one sensor sample and returns the (possibly unchanged) quantized rotation.
     * @param rollDegrees the raw, continuous, un-quantized roll (see [OrientationSensor]).
     * @param isReliable false near straight up/down; when false this call is a no-op that just returns
     *   [currentRotation] unchanged (see class KDoc).
     * @param nowMs a monotonic clock reading (e.g. `SensorEvent.timestamp` converted to ms, or
     *   `SystemClock.elapsedRealtime()`); only ever compared to other values from the same clock.
     */
    fun update(rollDegrees: Float, isReliable: Boolean, nowMs: Long): Int {
        if (!isReliable) return currentRotation

        val candidate = if (withinStickyZone(rollDegrees, currentRotation)) {
            currentRotation
        } else {
            nominalBand(rollDegrees)
        }

        if (candidate == currentRotation) {
            // Already settled here; drop any in-flight candidate for a different band.
            pendingArmed = false
            return currentRotation
        }

        if (!pendingArmed || pendingRotation != candidate) {
            // A fresh candidate (first sighting, or it changed mid-debounce): (re)start the timer.
            pendingRotation = candidate
            pendingSinceMs = nowMs
            pendingArmed = true
        } else if (nowMs - pendingSinceMs >= DEBOUNCE_MS) {
            currentRotation = candidate
            pendingArmed = false
        }
        return currentRotation
    }

    companion object {
        const val HYSTERESIS_DEGREES = 25f
        const val DEBOUNCE_MS = 400L

        private fun normalizeSigned180(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }

        /** The centre roll angle (degrees) of each quantized band, in the same signed [-180,180) space. */
        private fun bandCenter(band: Int): Float = when (band) {
            0 -> 0f
            90 -> 90f
            180 -> 180f
            270 -> -90f
            else -> 0f
        }

        /** The band a raw roll would nominally fall into, ignoring hysteresis (plain 90-degree buckets). */
        private fun nominalBand(roll: Float): Int {
            val r = normalizeSigned180(roll)
            return when {
                r >= -45f && r < 45f -> 0
                r >= 45f && r < 135f -> 90
                r < -45f && r >= -135f -> 270
                else -> 180
            }
        }

        /** Whether [roll] is still within [band]'s hysteresis-widened (45 + [HYSTERESIS_DEGREES]) range. */
        private fun withinStickyZone(roll: Float, band: Int): Boolean {
            val diff = abs(normalizeSigned180(roll - bandCenter(band)))
            return diff < (45f + HYSTERESIS_DEGREES)
        }
    }
}

/**
 * Re-references a raw roll reading (relative to natural-portrait-upright, see [OrientationSensor]) onto
 * the *quantized physical device rotation* so a level hold reads ~0 degrees regardless of which of the
 * four orientations the phone is actually held in — e.g. a phone held level in landscape has a raw roll
 * of ~90 (or ~-90) but, once [deviceRotationDegrees] has settled on that same band, this returns ~0.
 *
 * This is a plain subtraction (with signed-angle wraparound): the quantized rotation *is* the coarse
 * component of the roll, so the residual around it is exactly what the horizon/level analyzers care
 * about — fine tilt away from whichever orientation the phone is being held in, not a fixed axis.
 */
fun referenceRollToDeviceRotation(rawRollDegrees: Float, deviceRotationDegrees: Int): Float {
    var d = (rawRollDegrees - deviceRotationDegrees) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return d
}
