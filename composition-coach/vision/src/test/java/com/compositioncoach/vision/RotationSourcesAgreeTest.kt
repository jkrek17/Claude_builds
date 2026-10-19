package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The platform path (OrientationEventListener degrees -> [PlatformRotationQuantizer]) and the gravity
 * fallback (up-in-device-axes -> [gravityToRollDegrees] -> [DeviceRotationQuantizer]) must agree for the four
 * holds, and both must produce the app's `Surface.ROTATION_*` (counter-clockwise from natural) sense.
 *
 * Expected values come from the physical holds, not from the code:
 * ```
 * hold                listener (clockwise)   up in device axes (g = 9.81)   Surface sense
 * portrait            0                      ( 0,  g, 0)                    0
 * right edge up       270 (= 90 ccw)         ( g,  0, 0)                    90
 * upside down         180                    ( 0, -g, 0)                    180
 * left edge up        90  (= 90 cw)          (-g,  0, 0)                    270
 * ```
 */
class RotationSourcesAgreeTest {

    private data class Hold(
        val name: String,
        val listenerDegrees: Int,
        val gx: Float,
        val gy: Float,
        val surfaceRotation: Int,
    )

    private val g = 9.81f

    private val holds = listOf(
        Hold("portrait", 0, 0f, g, 0),
        Hold("right edge up", 270, g, 0f, 90),
        Hold("upside down", 180, 0f, -g, 180),
        Hold("left edge up", 90, -g, 0f, 270),
    )

    /** A session that starts in portrait and is then turned to [listenerDegrees] and held. */
    private fun platformRotation(listenerDegrees: Int): Int {
        val q = PlatformRotationQuantizer()
        q.update(0, 0L)
        var t = 1_000L
        q.update(listenerDegrees, t)
        t += PlatformRotationQuantizer.DEBOUNCE_MS
        return q.update(listenerDegrees, t)
    }

    private fun gravityRotation(gx: Float, gy: Float): Int {
        val q = DeviceRotationQuantizer()
        val roll = gravityToRollDegrees(gx, gy)
        q.update(roll, isReliable = true, nowMs = 0L)
        return q.update(roll, isReliable = true, nowMs = DeviceRotationQuantizer.DEBOUNCE_MS + 1)
    }

    @Test
    fun `platform listener degrees give Surface sense for each hold`() {
        for (h in holds) assertEquals(h.name, h.surfaceRotation, platformRotation(h.listenerDegrees))
    }

    @Test
    fun `listener sequence 0 270 180 90 yields 0 90 180 270`() {
        val q = PlatformRotationQuantizer()
        var t = 0L
        val out = intArrayOf(0, 270, 180, 90).map { deg ->
            q.update(deg, t)
            t += PlatformRotationQuantizer.DEBOUNCE_MS
            val r = q.update(deg, t)
            t += 700
            r
        }
        assertEquals(listOf(0, 90, 180, 270), out)
    }

    @Test
    fun `gravity fallback gives the same rotation for each hold`() {
        for (h in holds) assertEquals(h.name, h.surfaceRotation, gravityRotation(h.gx, h.gy))
    }

    @Test
    fun `platform and gravity paths agree for every hold`() {
        for (h in holds) {
            assertEquals(h.name, gravityRotation(h.gx, h.gy), platformRotation(h.listenerDegrees))
        }
    }

    @Test
    fun `roll stays physical-up referenced under the platform rotation`() {
        for (h in holds) {
            val rotation = platformRotation(h.listenerDegrees)
            val rawRoll = gravityToRollDegrees(h.gx, h.gy)
            assertEquals(h.name, 0f, referenceRollToDeviceRotation(rawRoll, rotation), 0.001f)
            // A 5 degree clockwise horizon tilt reads +5 in every hold.
            assertEquals(h.name, 5f, referenceRollToDeviceRotation(rawRoll + 5f, rotation), 0.001f)
        }
    }
}
