package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceRotationQuantizerTest {

    @Test
    fun `starts at 0 and stays there while roll is near level`() {
        val q = DeviceRotationQuantizer()
        var t = 0L
        for (roll in intArrayOf(0, 5, -5, 10, -10)) {
            assertEquals(0, q.update(roll.toFloat(), isReliable = true, nowMs = t))
            t += 50
        }
    }

    @Test
    fun `does not flip near the 45 degree boundary without crossing the hysteresis band`() {
        val q = DeviceRotationQuantizer()
        // 45 + 25 hysteresis = must exceed 70 degrees before a different band is even a candidate.
        var t = 0L
        listOf(40f, 50f, 60f, 65f, 69f).forEach { roll ->
            q.update(roll, isReliable = true, nowMs = t)
            t += 50
        }
        assertEquals("should still read the original band inside the sticky zone", 0, q.currentRotation)
    }

    @Test
    fun `commits to a new band once past hysteresis and held for the debounce window`() {
        val q = DeviceRotationQuantizer()
        var t = 0L
        q.update(90f, isReliable = true, nowMs = t) // candidate armed at t=0
        assertEquals("must not commit before the debounce window elapses", 0, q.currentRotation)
        t += 399
        q.update(90f, isReliable = true, nowMs = t)
        assertEquals("399ms < 400ms debounce: still not committed", 0, q.currentRotation)
        t += 2 // total 401ms since the candidate was first armed
        val result = q.update(90f, isReliable = true, nowMs = t)
        assertEquals(90, result)
        assertEquals(90, q.currentRotation)
    }

    @Test
    fun `a candidate that changes mid-debounce restarts the timer`() {
        val q = DeviceRotationQuantizer()
        var t = 0L
        q.update(90f, isReliable = true, nowMs = t) // candidate=90 armed at t=0
        t += 300
        q.update(-90f, isReliable = true, nowMs = t) // candidate flips to 270; timer restarts at t=300
        t += 300 // only 300ms since the 270 candidate was armed
        assertEquals("270 candidate has not been held for a full debounce window yet", 0, q.update(-90f, isReliable = true, nowMs = t))
        t += 150 // now 450ms since armed
        assertEquals(270, q.update(-90f, isReliable = true, nowMs = t))
    }

    @Test
    fun `unreliable readings freeze the current value instead of guessing`() {
        val q = DeviceRotationQuantizer()
        var t = 0L
        q.update(90f, isReliable = true, nowMs = t)
        t += 500
        assertEquals(90, q.update(90f, isReliable = true, nowMs = t))

        // A wild, numerically-unstable roll while pointing near straight up/down must not move the band.
        t += 100
        assertEquals(90, q.update(-40f, isReliable = false, nowMs = t))
        t += 500
        assertEquals(90, q.update(-40f, isReliable = false, nowMs = t))
    }

    @Test
    fun `settles through all four quantized bands from a full clockwise sweep`() {
        val q = DeviceRotationQuantizer()
        var t = 0L
        fun settle(roll: Float): Int {
            var result = 0
            repeat(10) {
                result = q.update(roll, isReliable = true, nowMs = t)
                t += 100
            }
            return result
        }
        assertEquals(0, settle(0f))
        assertEquals(90, settle(90f))
        assertEquals(180, settle(180f))
        assertEquals(270, settle(-90f))
        assertEquals(0, settle(0f))
    }

    // --- Roll re-referencing (a phone held level in landscape reports roll ~= 0) ------------------------

    @Test
    fun `level landscape hold reads roll approximately 0 once the device rotation has settled`() {
        val q = DeviceRotationQuantizer()
        var t = 0L
        // Simulate the phone being rotated to a level landscape hold: raw roll settles around 90 degrees.
        repeat(10) {
            q.update(90f, isReliable = true, nowMs = t)
            t += 100
        }
        assertEquals(90, q.currentRotation)
        assertEquals(0f, referenceRollToDeviceRotation(90f, q.currentRotation), 1e-4f)
    }

    @Test
    fun `a slight tilt away from level landscape reports a small residual roll, not 90`() {
        val q = DeviceRotationQuantizer()
        var t = 0L
        repeat(10) {
            q.update(88f, isReliable = true, nowMs = t)
            t += 100
        }
        assertEquals(90, q.currentRotation)
        assertEquals(-2f, referenceRollToDeviceRotation(88f, q.currentRotation), 1e-4f)
    }

    @Test
    fun `re-referencing wraps around correctly at the 180 boundary`() {
        assertEquals(-179f, referenceRollToDeviceRotation(1f, 180), 1e-4f)
        assertEquals(0f, referenceRollToDeviceRotation(180f, 180), 1e-4f)
        assertEquals(0f, referenceRollToDeviceRotation(-90f, 270), 1e-4f)
    }
}
