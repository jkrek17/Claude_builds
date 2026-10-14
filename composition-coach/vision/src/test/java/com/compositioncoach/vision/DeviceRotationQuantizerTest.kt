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

    // --- Anchored to gravity, not to an intermediate roll -------------------------------------------------
    // Everything above feeds the quantizer a roll number directly, which only proves it buckets correctly
    // *given* that number's sign. These drive the whole thing from the raw gravity vector instead, so a
    // sign error anywhere between "which way is up" and "which Surface.ROTATION_* band" is caught here.
    //
    // Android's device axes: +x out the RIGHT edge, +y out the TOP edge. A stationary device reports the
    // direction of up in those axes, so the four holds are literally the four unit axes in the xy plane.

    @Test
    fun `each physical hold's gravity vector quantizes to that hold's Surface_ROTATION band`() {
        // portrait: up is out the top edge      -> (0, +g)  -> ROTATION_0
        assertEquals(0, settle(gravityToRollDegrees(0f, G)))
        // right edge up (ROTATION_90 is defined as 90 degrees CCW from natural) -> (+g, 0)
        assertEquals(90, settle(gravityToRollDegrees(G, 0f)))
        // upside down: up is out the bottom edge -> (0, -g) -> ROTATION_180
        assertEquals(180, settle(gravityToRollDegrees(0f, -G)))
        // left edge up -> (-g, 0) -> ROTATION_270
        assertEquals(270, settle(gravityToRollDegrees(-G, 0f)))
    }

    @Test
    fun `90 and 270 are not swapped, which is the mistake this whole file exists to prevent`() {
        assertEquals(90, settle(gravityToRollDegrees(G, 0f)))
        assertEquals(270, settle(gravityToRollDegrees(-G, 0f)))
    }

    @Test
    fun `a level hold reads roll ~0 in every orientation, straight from gravity`() {
        for ((gx, gy) in listOf(0f to G, G to 0f, 0f to -G, -G to 0f)) {
            val raw = gravityToRollDegrees(gx, gy)
            assertEquals("gravity=($gx, $gy)", 0f, referenceRollToDeviceRotation(raw, settle(raw)), 1e-3f)
        }
    }

    @Test
    fun `a 5 degree clockwise horizon tilt reads +5 in every orientation, straight from gravity`() {
        // Turning the camera 5 degrees further counter-clockwise sweeps a fixed scene 5 degrees clockwise
        // inside the frame, so this is the gravity vector of "the hold, plus 5 degrees CCW".
        for (theta in intArrayOf(0, 90, 180, 270)) {
            val a = Math.toRadians(theta + 5.0)
            val raw = gravityToRollDegrees((G * kotlin.math.sin(a)).toFloat(), (G * kotlin.math.cos(a)).toFloat())
            val band = settle(raw)
            assertEquals("theta=$theta band", theta, band)
            assertEquals("theta=$theta roll", 5f, referenceRollToDeviceRotation(raw, band), 1e-3f)
        }
    }

    @Test
    fun `pitching the camera at the ground makes the reading unreliable rather than a random band`() {
        // Rear camera pointed straight down: device +z swings up, so up in device axes is (0, 0, +g) and
        // roll is numerically meaningless. gravityReadingIsReliable must say so; the quantizer then
        // freezes rather than committing to whatever atan2 of two near-zero numbers produced.
        assertEquals(false, gravityReadingIsReliable(0f, 0.02f, 1f))
        assertEquals(true, gravityReadingIsReliable(0f, 1f, 0f))
        // The accelerometer fallback path passes a ~9.81-magnitude vector; the scale keeps the same
        // ~25-degree cone rather than making the check effectively never fire.
        assertEquals(false, gravityReadingIsReliable(0f, 0.2f, 9.8f, magnitudeScale = 9.81f))
        assertEquals(true, gravityReadingIsReliable(0f, 9.81f, 0f, magnitudeScale = 9.81f))
    }

    /** Feeds one reading through the quantizer until its debounce window has elapsed. */
    private fun settle(rollDegrees: Float): Int {
        val q = DeviceRotationQuantizer()
        q.update(rollDegrees, isReliable = true, nowMs = 0L)
        return q.update(rollDegrees, isReliable = true, nowMs = DeviceRotationQuantizer.DEBOUNCE_MS + 1)
    }

    private companion object {
        const val G = 9.81f
    }
}
