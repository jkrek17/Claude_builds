package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformRotationQuantizerTest {

    private val unknown = PlatformRotationQuantizer.ORIENTATION_UNKNOWN

    /** Seeds a quantizer on [degrees] (first valid vote is adopted immediately) at t = 0. */
    private fun seeded(degrees: Int): PlatformRotationQuantizer =
        PlatformRotationQuantizer().also { it.update(degrees, 0L) }

    /** Holds [degrees] from [startMs] long enough to pass the debounce window; returns the last output. */
    private fun hold(q: PlatformRotationQuantizer, degrees: Int, startMs: Long): Int {
        q.update(degrees, startMs)
        return q.update(degrees, startMs + PlatformRotationQuantizer.DEBOUNCE_MS)
    }

    @Test
    fun `listener clockwise degrees map to Surface counter-clockwise rotation`() {
        assertEquals(0, listenerClockwiseToSurfaceRotation(0))
        assertEquals(270, listenerClockwiseToSurfaceRotation(90))
        assertEquals(180, listenerClockwiseToSurfaceRotation(180))
        assertEquals(90, listenerClockwiseToSurfaceRotation(270))
        assertEquals(0, listenerClockwiseToSurfaceRotation(360))
    }

    @Test
    fun `worked example - turned counter-clockwise right edge up reports about 270 and is Surface 90`() {
        // Right edge points up == turned 90 degrees counter-clockwise == turned 270 clockwise.
        val q = PlatformRotationQuantizer()
        assertEquals(90, q.update(268, 0L))
        assertEquals(90, q.currentRotation)
    }

    @Test
    fun `initial value is 0 and nothing is known until a usable reading`() {
        val q = PlatformRotationQuantizer()
        assertEquals(0, q.currentRotation)
        assertFalse(q.hasReading)
        assertEquals(0, q.update(unknown, 0L))
        assertFalse(q.hasReading)
    }

    @Test
    fun `first valid reading is adopted immediately`() {
        val q = PlatformRotationQuantizer()
        assertEquals(180, q.update(182, 0L))
        assertTrue(q.hasReading)
    }

    @Test
    fun `bands are centred on 0 90 180 270 and wrap at 360`() {
        val cases = mapOf(
            0 to 0, 20 to 0, 340 to 0, 359 to 0,
            90 to 270, 70 to 270, 110 to 270,
            180 to 180, 160 to 180, 200 to 180,
            270 to 90, 250 to 90, 290 to 90,
        )
        for ((listener, expectedSurface) in cases) {
            assertEquals("listener $listener", expectedSurface, PlatformRotationQuantizer().update(listener, 0L))
        }
    }

    @Test
    fun `a new band needs readings within 35 degrees of its centre held for 300 ms`() {
        val q = seeded(0)
        assertEquals(0, q.update(90, 1_000L)) // arms
        assertTrue(q.hasPendingBand)
        assertEquals(0, q.update(90, 1_299L))
        assertEquals(270, q.update(90, 1_300L)) // listener 90 -> Surface 270
        assertFalse(q.hasPendingBand)
    }

    @Test
    fun `45 degree boundaries never flap - dead zone votes for nothing`() {
        val q = seeded(0)
        var t = 0L
        // Wander across the 45 degree boundary, never getting within 35 of 90: 36..54 is the dead zone.
        for (deg in intArrayOf(36, 44, 46, 54, 40, 50, 44, 46)) {
            t += 200
            assertEquals("deg $deg", 0, q.update(deg, t))
        }
        assertFalse(q.hasPendingBand)
    }

    @Test
    fun `dead zone reading restarts the debounce`() {
        val q = seeded(0)
        q.update(90, 1_000L) // arms 90
        q.update(50, 1_200L) // dead zone: breaks continuity
        assertFalse(q.hasPendingBand)
        assertEquals(0, q.update(90, 1_400L)) // re-arms at 1400
        assertEquals(0, q.update(90, 1_699L))
        assertEquals(270, q.update(90, 1_700L))
    }

    @Test
    fun `a candidate that changes mid-debounce restarts the timer`() {
        val q = seeded(0)
        q.update(90, 1_000L) // arms 90
        q.update(180, 1_250L) // now 180
        assertEquals(0, q.update(180, 1_500L)) // only 250 ms since 180 armed
        assertEquals(180, q.update(180, 1_550L))
    }

    @Test
    fun `returning to the current band cancels a pending change`() {
        val q = seeded(0)
        q.update(90, 1_000L)
        q.update(5, 1_200L)
        assertFalse(q.hasPendingBand)
        assertEquals(0, q.update(90, 1_250L)) // timer restarted, not carried over
        assertEquals(0, q.update(90, 1_549L))
    }

    @Test
    fun `unknown keeps the last value and breaks a pending debounce`() {
        val q = seeded(270) // Surface 90
        assertEquals(90, q.update(unknown, 100L))
        assertEquals(90, q.update(unknown, 10_000L))
        q.update(0, 11_000L) // arms portrait
        q.update(unknown, 11_200L) // flat: continuity broken
        assertEquals(90, q.update(0, 11_400L))
        assertEquals(90, q.update(0, 11_699L))
        assertEquals(0, q.update(0, 11_700L))
    }

    @Test
    fun `out of range readings are treated as unknown`() {
        val q = seeded(180)
        assertEquals(180, q.update(400, 100L))
        assertEquals(180, q.update(-7, 200L))
    }

    @Test
    fun `restart adopts the next valid reading immediately and keeps the old value until then`() {
        val q = seeded(270) // Surface 90
        q.onRestart()
        assertEquals(90, q.currentRotation)
        assertEquals(90, q.update(unknown, 5L))
        assertEquals(0, q.update(2, 10L))
    }

    @Test
    fun `full turn sequence through every hold`() {
        val q = PlatformRotationQuantizer()
        var t = 0L
        // portrait -> right edge up (listener 270) -> upside down -> left edge up (listener 90) -> portrait
        assertEquals(0, hold(q, 0, t))
        t += 1_000
        assertEquals(90, hold(q, 270, t))
        t += 1_000
        assertEquals(180, hold(q, 180, t))
        t += 1_000
        assertEquals(270, hold(q, 90, t))
        t += 1_000
        assertEquals(0, hold(q, 0, t))
    }
}
