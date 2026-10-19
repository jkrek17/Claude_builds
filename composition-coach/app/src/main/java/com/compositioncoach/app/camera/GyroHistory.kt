package com.compositioncoach.app.camera

import com.compositioncoach.vision.AngularDelta

/**
 * A bounded history of raw gyroscope samples (rad/s about the device axes, sensor-event timestamps in ns) that
 * can integrate the rotation between two instants, for rotation-only frame alignment (Motion stacks, focus
 * stacks). Each sample's rate is taken to hold from the previous sample's timestamp to its own (the gyro's
 * reporting interval, ~10 ms), and only the part of that span inside the asked interval counts. Small-angle:
 * the three axes are integrated independently, which is exact enough for the few degrees a steady hold drifts.
 * Thread-safe (the sensor thread writes, capture code reads).
 */
class GyroHistory(private val capacity: Int = DEFAULT_CAPACITY) {
    private val timestamps = LongArray(capacity)
    private val rates = FloatArray(capacity * 3)
    private var next = 0
    private var size = 0

    @Synchronized
    fun add(timestampNs: Long, wx: Float, wy: Float, wz: Float) {
        if (size > 0 && timestampNs <= timestamps[(next - 1 + capacity) % capacity]) return
        timestamps[next] = timestampNs
        rates[next * 3] = wx
        rates[next * 3 + 1] = wy
        rates[next * 3 + 2] = wz
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    @Synchronized
    fun clear() {
        next = 0
        size = 0
    }

    /** Timestamp of the newest sample, or null when empty. */
    @Synchronized
    fun latestTimestampNs(): Long? = if (size == 0) null else timestamps[(next - 1 + capacity) % capacity]

    /**
     * Rotation from [fromNs] to [toNs] (negative when [toNs] < [fromNs]), or null when the history does not
     * reach back to the earlier instant (the sensor was not running, or it was too long ago).
     */
    @Synchronized
    fun deltaBetween(fromNs: Long, toNs: Long): AngularDelta? {
        if (size < 2) return null
        val start = minOf(fromNs, toNs)
        val end = maxOf(fromNs, toNs)
        val oldest = (next - size + capacity) % capacity
        if (start < timestamps[oldest]) return null
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (k in 1 until size) {
            val i = (oldest + k) % capacity
            val prev = timestamps[(i - 1 + capacity) % capacity]
            val cur = timestamps[i]
            val a = maxOf(prev, start)
            val b = minOf(cur, end)
            if (b <= a) continue
            val dt = (b - a) / 1e9
            x += rates[i * 3] * dt
            y += rates[i * 3 + 1] * dt
            z += rates[i * 3 + 2] * dt
        }
        val sign = if (toNs >= fromNs) 1f else -1f
        return AngularDelta(sign * x.toFloat(), sign * y.toFloat(), sign * z.toFloat())
    }

    companion object {
        /** 8 s at the 100 Hz the MotionSensor asks for: a 5 s Motion hold plus margin. */
        const val DEFAULT_CAPACITY = 800
    }
}
