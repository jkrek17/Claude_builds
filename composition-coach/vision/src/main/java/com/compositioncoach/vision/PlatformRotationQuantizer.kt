package com.compositioncoach.vision

/**
 * Pure (no Android types) quantizer for the platform's own device-rotation signal,
 * `android.view.OrientationEventListener.onOrientationChanged(orientation)`, as used by every production
 * camera app. [DeviceRotationSource] owns the listener and feeds readings in here; this class holds all the
 * logic so it can be unit-tested on the JVM.
 *
 * ### Input
 * `orientation` is 0..359 degrees, **clockwise** from natural portrait, as seen by the person looking at the
 * screen ("the phone has been turned this many degrees clockwise"), or [ORIENTATION_UNKNOWN] (-1) when the
 * phone is lying flat and no meaningful rotation exists.
 *
 * ### Output convention and its derivation
 * The app's `deviceRotationDegrees` (`FrameAnalysis.deviceRotationDegrees`, [UprightRotation], the chrome
 * counter-rotation, the capture EXIF mapping) is in `Surface.ROTATION_*` sense: **counter-clockwise** from
 * natural portrait, so `Surface.ROTATION_90` is "turned 90 degrees counter-clockwise, right edge points up".
 * The listener reports the opposite sense (clockwise), so for a quantized clockwise band `q`:
 * ```
 * deviceRotation = (360 - q) % 360        // see [listenerClockwiseToSurfaceRotation]
 * ```
 * Worked example: the user turns the phone counter-clockwise so its RIGHT edge points up. Turning a phone
 * counter-clockwise by 90 degrees is the same as turning it clockwise by 270, so the listener reports
 * ~270 (`q = 270`). Surface sense for that same hold is `ROTATION_90`, and `(360 - 270) % 360 = 90`. ✓
 * The other three holds: portrait `0 -> 0`; upside down `180 -> 180`; left edge up (turned 90 clockwise)
 * listener ~90 `-> (360 - 90) % 360 = 270 = ROTATION_270`. ✓ (This is the same table as the standard
 * camera-app idiom that maps listener 225..314 to `ROTATION_90` and 45..134 to `ROTATION_270`.)
 *
 * ### Quantization with hysteresis and debounce
 * The four bands are centred on 0/90/180/270 (clockwise). A reading *can only vote* for a band when it is
 * within [ADOPT_RADIUS_DEGREES] (35) of that band's centre; readings in the 10-degree-wide dead zone around
 * each 45-degree boundary (35..55 from a centre) vote for nothing, so a phone resting at ~45 degrees never
 * flaps. A band different from the current one is adopted only after readings have voted for it
 * *continuously* for [DEBOUNCE_MS] (300 ms): any reading that votes for another band, lands in a dead zone,
 * or is unknown restarts the wait. [ORIENTATION_UNKNOWN] otherwise keeps the last value.
 *
 * The first valid vote after construction or [onRestart] is adopted immediately: there is no previous band
 * worth protecting, and it makes the very first analysed frames carry the real hold instead of 0.
 *
 * The platform only calls the listener when the reading *changes*, so a phone that is turned and then held
 * perfectly still can go quiet during the debounce window. [hasPendingBand] lets the caller re-evaluate
 * the last reading on a timer ([DeviceRotationSource] does) so adoption doesn't depend on hand jitter.
 */
class PlatformRotationQuantizer {

    /** The adopted rotation in `Surface.ROTATION_*` sense (0/90/180/270; counter-clockwise). 0 until known. */
    @Volatile
    var currentRotation: Int = 0
        private set

    /** Whether any valid reading has been adopted since construction. */
    @Volatile
    var hasReading: Boolean = false
        private set

    /** True while a different band is collecting its debounce time. */
    val hasPendingBand: Boolean get() = pendingBand != NO_BAND

    private var currentBand: Int = 0 // clockwise band, 0/90/180/270
    private var pendingBand: Int = NO_BAND
    private var pendingSinceMs: Long = 0L
    private var seeded: Boolean = false

    /**
     * Feeds one reading and returns the (possibly unchanged) adopted rotation, in `Surface.ROTATION_*` sense.
     * @param orientationDegrees the listener's value: 0..359 clockwise, or [ORIENTATION_UNKNOWN].
     * @param nowMs a monotonic clock reading in ms, only ever compared with other values from that clock.
     */
    fun update(orientationDegrees: Int, nowMs: Long): Int {
        if (orientationDegrees < 0 || orientationDegrees >= 360) {
            pendingBand = NO_BAND // unknown (flat) or out-of-range: keep the last value
            return currentRotation
        }
        val band = nearestBand(orientationDegrees)
        if (circularDistance(orientationDegrees, band) > ADOPT_RADIUS_DEGREES) {
            pendingBand = NO_BAND // dead zone around a 45-degree boundary: vote for nothing
            return currentRotation
        }
        if (!seeded) {
            adopt(band)
            seeded = true
            return currentRotation
        }
        if (band == currentBand) {
            pendingBand = NO_BAND
            return currentRotation
        }
        if (pendingBand != band) {
            pendingBand = band
            pendingSinceMs = nowMs
        } else if (nowMs - pendingSinceMs >= DEBOUNCE_MS) {
            adopt(band)
        }
        return currentRotation
    }

    /** Call when the listener is (re)enabled: the next valid reading is adopted at once. */
    fun onRestart() {
        seeded = false
        pendingBand = NO_BAND
    }

    private fun adopt(band: Int) {
        currentBand = band
        currentRotation = listenerClockwiseToSurfaceRotation(band)
        hasReading = true
        pendingBand = NO_BAND
    }

    companion object {
        /** `OrientationEventListener.ORIENTATION_UNKNOWN`, duplicated so this class stays Android-free. */
        const val ORIENTATION_UNKNOWN = -1

        /** A reading must be within this many degrees of a band's centre to vote for it. */
        const val ADOPT_RADIUS_DEGREES = 35

        const val DEBOUNCE_MS = 300L

        private const val NO_BAND = -1

        /** Nearest of 0/90/180/270 for a 0..359 clockwise reading. */
        internal fun nearestBand(degrees: Int): Int = (((degrees + 45) / 90) % 4) * 90

        private fun circularDistance(a: Int, b: Int): Int {
            val d = Math.abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }
    }
}

/**
 * Converts a clockwise-from-natural angle (what `OrientationEventListener` reports) into the app's
 * `Surface.ROTATION_*` counter-clockwise sense: `(360 - clockwise) % 360`. Applied to an already
 * quantized band (0/90/180/270) it yields 0/270/180/90; e.g. a listener reading of 270 (phone turned
 * counter-clockwise, right edge up) becomes 90. Works for any integer input (normalized into 0..359).
 */
fun listenerClockwiseToSurfaceRotation(clockwiseDegrees: Int): Int =
    (360 - ((clockwiseDegrees % 360) + 360) % 360) % 360
