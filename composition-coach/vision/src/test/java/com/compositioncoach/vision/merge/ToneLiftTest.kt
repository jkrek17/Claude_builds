package com.compositioncoach.vision.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneLiftTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun level(c: Int) = c and 0xFF

    @Test
    fun `no exposure change and no lift is the identity`() {
        assertTrue(ToneLift.isIdentity(ToneLift.lut(0f, 0f)))
        val px = intArrayOf(grey(0), grey(77), grey(255))
        ToneLift.apply(px, px.size, ToneLift.lut(0f, 0f))
        assertEquals(77, level(px[1]))
    }

    @Test
    fun `the shadow lift raises shadows most and leaves black and white alone`() {
        val lut = ToneLift.lut(0f, ToneLift.NIGHT_SHADOW_LIFT)
        assertFalse(ToneLift.isIdentity(lut))
        val px = intArrayOf(grey(0), grey(25), grey(85), grey(170), grey(255))
        ToneLift.apply(px, px.size, lut)
        assertEquals(0, level(px[0]))
        assertTrue("shadow ${level(px[1])}", level(px[1]) >= 25 + 10)
        assertTrue("lower mid ${level(px[2])}", level(px[2]) >= 85 + 20)
        val shadowGain = level(px[1]) / 25f
        val highlightGain = level(px[3]) / 170f
        assertTrue(shadowGain > highlightGain)
        assertEquals(255, level(px[4]))
    }

    @Test
    fun `the lift never inverts tones and never exceeds the gain cap`() {
        val lut = ToneLift.lut(0f, ToneLift.NIGHT_SHADOW_LIFT)
        var previous = 0f
        for (i in 0..255) {
            val out = i * lut[i]
            assertTrue("not monotonic at $i", out >= previous - 1e-3f)
            previous = out
            assertTrue(lut[i] <= ToneLift.MAX_GAIN)
        }
    }

    @Test
    fun `an exposure lift brightens midtones by about the asked stops and holds white`() {
        val lut = ToneLift.lut(1f / 3f, 0f)
        val px = intArrayOf(grey(60), grey(128), grey(255))
        ToneLift.apply(px, px.size, lut)
        // A third of a stop in linear light is about 11 percent in encoded value.
        assertEquals(60 * 1.11f, level(px[0]).toFloat(), 3f)
        assertEquals(128 * 1.11f, level(px[1]).toFloat(), 4f)
        assertTrue(level(px[2]) >= 250)
    }

    @Test
    fun `lifting preserves colour ratios`() {
        val px = intArrayOf((0xFF shl 24) or (40 shl 16) or (20 shl 8) or 10)
        ToneLift.apply(px, 1, ToneLift.lut(0f, ToneLift.NIGHT_SHADOW_LIFT))
        val r = (px[0] shr 16) and 0xFF
        val g = (px[0] shr 8) and 0xFF
        val b = px[0] and 0xFF
        assertEquals(2f, r.toFloat() / g, 0.15f)
        assertEquals(2f, g.toFloat() / b, 0.25f)
        assertTrue(r > 40)
    }
}
