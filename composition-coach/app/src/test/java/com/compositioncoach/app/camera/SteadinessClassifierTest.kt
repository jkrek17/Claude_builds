package com.compositioncoach.app.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class SteadinessClassifierTest {

    private val ms = 1_000_000L

    /** Feeds [speed] every 10 ms (100 Hz, the sensor's rate) for [durationMs] starting at [startMs]. */
    private fun SteadinessClassifier.feed(startMs: Long, durationMs: Long, speed: Float): Steadiness {
        var result = current
        var t = startMs
        while (t < startMs + durationMs) {
            result = update(t * ms, speed)
            t += 10
        }
        return result
    }

    @Test
    fun `classifies the three bands`() {
        assertEquals(Steadiness.STEADY, SteadinessClassifier().feed(0, 300, 0.03f))
        assertEquals(Steadiness.SLIGHT_MOTION, SteadinessClassifier().feed(0, 300, 0.2f))
        assertEquals(Steadiness.MOVING, SteadinessClassifier().feed(0, 300, 0.6f))
    }

    @Test
    fun `one spike inside a steady window does not flip to MOVING`() {
        val c = SteadinessClassifier()
        c.feed(0, 300, 0.02f)
        // A single 0.9 rad/s sample among ~15 quiet ones: RMS ~0.23, below the MOVING threshold.
        c.update(300 * ms, 0.9f)
        assertEquals(Steadiness.SLIGHT_MOTION, c.current)
    }

    @Test
    fun `leaving STEADY needs more than entering it (hysteresis)`() {
        val c = SteadinessClassifier()
        c.feed(0, 300, 0.05f)
        assertEquals(Steadiness.STEADY, c.current)
        // 0.10 is above the 0.08 entry threshold but below the 0.12 exit threshold: stays STEADY.
        assertEquals(Steadiness.STEADY, c.feed(300, 300, 0.10f))
        assertEquals(Steadiness.SLIGHT_MOTION, c.feed(600, 300, 0.15f))
        // ...and coming back, 0.10 is not low enough to re-enter STEADY.
        assertEquals(Steadiness.SLIGHT_MOTION, c.feed(900, 300, 0.10f))
        assertEquals(Steadiness.STEADY, c.feed(1200, 300, 0.05f))
    }

    @Test
    fun `leaving MOVING needs to drop below the lower exit threshold`() {
        val c = SteadinessClassifier()
        c.feed(0, 300, 0.5f)
        assertEquals(Steadiness.MOVING, c.current)
        assertEquals(Steadiness.MOVING, c.feed(300, 300, 0.30f))
        assertEquals(Steadiness.SLIGHT_MOTION, c.feed(600, 300, 0.20f))
    }

    @Test
    fun `the window forgets motion older than 150 ms`() {
        val c = SteadinessClassifier()
        c.feed(0, 200, 0.8f)
        assertEquals(Steadiness.MOVING, c.current)
        assertEquals(Steadiness.STEADY, c.feed(200, 200, 0.01f))
    }

    @Test
    fun `classify is a pure hysteresis step`() {
        val c = SteadinessClassifier()
        assertEquals(Steadiness.MOVING, c.classify(Steadiness.STEADY, 0.4f))
        assertEquals(Steadiness.STEADY, c.classify(Steadiness.MOVING, 0.05f))
        assertEquals(Steadiness.SLIGHT_MOTION, c.classify(Steadiness.SLIGHT_MOTION, 0.1f))
    }

    @Test
    fun `angular speed from a gyro vector and from quaternions`() {
        assertEquals(0.5f, SteadinessClassifier.angularSpeed(0.3f, 0.4f, 0f), 1e-6f)
        // 0.1 rad about z between two samples 0.1 s apart is 1 rad/s.
        val half = 0.05
        val q1 = floatArrayOf(1f, 0f, 0f, 0f)
        val q2 = floatArrayOf(kotlin.math.cos(half).toFloat(), 0f, 0f, kotlin.math.sin(half).toFloat())
        assertEquals(1f, SteadinessClassifier.angularSpeedFromQuaternions(q1, q2, 0.1f), 1e-3f)
        assertEquals(0f, SteadinessClassifier.angularSpeedFromQuaternions(q1, q2, 0f), 0f)
    }
}
