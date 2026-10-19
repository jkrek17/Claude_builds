package com.compositioncoach.app.camera

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * The pure half of [MotionSensor]: turns a stream of angular-speed samples (rad/s, from the gyroscope) into a
 * [Steadiness] class, with a short time window and hysteresis.
 *
 * ## Why angular speed, and why these thresholds
 * Hand-shake blur is dominated by camera *rotation*, not translation: a rotation rate `w` (rad/s) during an
 * exposure `t` smears the image by about `w * t * f` pixels, where `f` is the focal length in pixels (~2,700
 * px for a phone main camera at 4080 px wide). At 0.08 rad/s and 1/60 s that is ~3.6 px before stabilization;
 * OIS removes most low-frequency shake, leaving roughly a pixel, i.e. "sharp". At 0.35 rad/s the residual
 * after OIS is several pixels at indoor shutter speeds, i.e. visibly soft. So:
 *  - [Steadiness.STEADY] below [steadyEnter] (0.08 rad/s),
 *  - [Steadiness.MOVING] above [movingEnter] (0.35 rad/s),
 *  - [Steadiness.SLIGHT_MOTION] in between.
 *
 * ## Window and hysteresis
 * The statistic is the RMS angular speed over the last [windowMs] (150 ms, about one breath-hold's worth of
 * tremor cycles at 8-12 Hz), so one noisy gyro sample cannot flip the class. Hysteresis keeps the "Hold still"
 * hint from flickering at a boundary: once STEADY, the RMS must exceed [steadyExit] (above the entry
 * threshold) to leave it; once MOVING, it must fall below [movingExit] (below the entry threshold) to leave.
 */
class SteadinessClassifier(
    val steadyEnter: Float = 0.08f,
    val steadyExit: Float = 0.12f,
    val movingEnter: Float = 0.35f,
    val movingExit: Float = 0.26f,
    val windowMs: Long = 150L,
) {
    init {
        require(steadyEnter <= steadyExit && steadyExit < movingExit && movingExit <= movingEnter)
    }

    private val timestamps = ArrayDeque<Long>()
    private val squares = ArrayDeque<Float>()
    private var sumSquares = 0.0

    var current: Steadiness = Steadiness.STEADY
        private set

    /** RMS angular speed (rad/s) over the current window; 0 before the first sample. */
    val windowRms: Float
        get() = if (squares.isEmpty()) 0f else sqrt((sumSquares / squares.size).coerceAtLeast(0.0)).toFloat()

    /** Feeds one sample at [timestampNanos] (monotonic) with angular speed [radPerSecond]; returns the new class. */
    fun update(timestampNanos: Long, radPerSecond: Float): Steadiness {
        val speed = if (radPerSecond.isFinite()) abs(radPerSecond) else 0f
        timestamps.addLast(timestampNanos)
        squares.addLast(speed * speed)
        sumSquares += speed * speed
        val cutoff = timestampNanos - windowMs * 1_000_000L
        while (timestamps.size > 1 && timestamps.first() < cutoff) {
            timestamps.removeFirst()
            sumSquares -= squares.removeFirst()
        }
        current = classify(current, windowRms)
        return current
    }

    fun reset() {
        timestamps.clear()
        squares.clear()
        sumSquares = 0.0
        current = Steadiness.STEADY
    }

    /** One hysteresis step from [previous] given the window's RMS speed [rms]. */
    fun classify(previous: Steadiness, rms: Float): Steadiness = when (previous) {
        Steadiness.STEADY -> when {
            rms > movingEnter -> Steadiness.MOVING
            rms > steadyExit -> Steadiness.SLIGHT_MOTION
            else -> Steadiness.STEADY
        }
        Steadiness.SLIGHT_MOTION -> when {
            rms > movingEnter -> Steadiness.MOVING
            rms < steadyEnter -> Steadiness.STEADY
            else -> Steadiness.SLIGHT_MOTION
        }
        Steadiness.MOVING -> when {
            rms < steadyEnter -> Steadiness.STEADY
            rms < movingExit -> Steadiness.SLIGHT_MOTION
            else -> Steadiness.MOVING
        }
    }

    companion object {
        /** Gyroscope reading (rad/s about x, y, z) to angular speed. */
        fun angularSpeed(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

        /**
         * Angular speed between two unit quaternions `(w, x, y, z)` sampled [dtSeconds] apart, for the
         * `TYPE_GAME_ROTATION_VECTOR` fallback on devices without a raw gyroscope: the rotation angle between
         * them is `2 acos(|q1 . q2|)`.
         */
        fun angularSpeedFromQuaternions(q1: FloatArray, q2: FloatArray, dtSeconds: Float): Float {
            if (dtSeconds <= 0f) return 0f
            val dot = abs(q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3]).coerceAtMost(1f)
            return (2.0 * acos(dot.toDouble())).toFloat() / dtSeconds
        }
    }
}
