package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameAccumulatorTest {

    private val w = 8
    private val h = 6

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun solid(c: Int) = IntArray(w * h) { c }
    private fun withDot(background: Int, dot: Int, x: Int, y: Int) = solid(background).also { it[y * w + x] = dot }
    private fun red(c: Int) = (c shr 16) and 0xFF

    @Test
    fun `average of constant frames is their mean`() {
        val acc = FrameAccumulator(StackBlend.AVERAGE, w, h, expectedFrames = 4)
        acc.add(solid(rgb(10, 20, 30)))
        acc.add(solid(rgb(20, 40, 60)))
        acc.add(solid(rgb(30, 60, 90)))
        acc.add(solid(rgb(40, 80, 120)))
        val out = acc.result()!!
        assertEquals(4, out.frames)
        assertTrue(out.rgb.all { it == rgb(25, 50, 75) })
    }

    @Test
    fun `lighten keeps the brightest moving dot as a trail`() {
        val acc = FrameAccumulator(StackBlend.LIGHTEN, w, h, expectedFrames = 4)
        val dark = rgb(5, 5, 5)
        val light = rgb(250, 240, 200)
        for (x in 1..4) acc.add(withDot(dark, light, x, 2))
        val out = acc.result()!!.rgb
        for (x in 1..4) assertEquals(light, out[2 * w + x])
        assertEquals(dark, out[2 * w + 6])
        assertEquals(dark, out[0])
    }

    @Test
    fun `median removes a transient present in a minority of frames`() {
        val acc = FrameAccumulator(StackBlend.MEDIAN, w, h, expectedFrames = 5)
        val scene = rgb(100, 110, 120)
        val person = rgb(240, 10, 10)
        acc.add(solid(scene))
        acc.add(withDot(scene, person, 3, 3))
        acc.add(withDot(scene, person, 4, 3))
        acc.add(solid(scene))
        acc.add(solid(scene))
        val out = acc.result()!!.rgb
        assertTrue(out.all { it == scene })
    }

    @Test
    fun `median store is bounded and decimates adaptively to span the hold`() {
        val acc = FrameAccumulator(StackBlend.MEDIAN, 100, 100, medianBudgetBytes = 10L * 100 * 100 * 4)
        assertEquals(10, acc.medianCapacity)
        repeat(100) { acc.add(IntArray(100 * 100) { rgb(it % 256, 0, 0) }) }
        assertEquals(100, acc.framesOffered)
        val kept = acc.result()!!.frames
        assertTrue("kept $kept", kept in 5..10)
        assertTrue(FrameAccumulator(StackBlend.MEDIAN, 2, 2, 1000).medianCapacity <= FrameAccumulator.MAX_MEDIAN_FRAMES)
        assertTrue(FrameAccumulator(StackBlend.MEDIAN, 2000, 2000, 1000).medianCapacity >= FrameAccumulator.MIN_MEDIAN_FRAMES)
    }

    @Test
    fun `median decimation keeps frames from the whole hold, not only the latest`() {
        // Frame i is the flat value i: whatever is kept, the median must come from the middle of the hold.
        val acc = FrameAccumulator(StackBlend.MEDIAN, 4, 4, medianBudgetBytes = 8L * 4 * 4 * 4)
        repeat(200) { i -> acc.add(IntArray(16) { rgb(i.coerceAtMost(255), 0, 0) }) }
        val median = red(acc.result()!!.rgb[0])
        assertTrue("median $median", median in 70..130)
    }

    @Test
    fun `average of identical frames converges at the first comparison, noise does not`() {
        val still = FrameAccumulator(StackBlend.AVERAGE, 16, 12)
        repeat(15) { still.add(IntArray(16 * 12) { rgb(90, 90, 90) }) }
        assertFalse("needs a half-length snapshot first", still.converged)
        still.add(IntArray(16 * 12) { rgb(90, 90, 90) })
        assertTrue(still.converged)
        assertEquals(1f, still.convergence, 0f)

        val noisy = FrameAccumulator(StackBlend.AVERAGE, 64, 48)
        var seed = 12345
        fun noise(): Int { seed = seed * 1103515245 + 12345; return (seed ushr 8) and 0xFF }
        repeat(64) { noisy.add(IntArray(64 * 48) { rgb(noise(), noise(), noise()) }) }
        assertFalse(noisy.converged)
        assertTrue(noisy.convergence in 0f..1f)
    }

    @Test
    fun `lighten converges once new frames stop raising pixels`() {
        val acc = FrameAccumulator(StackBlend.LIGHTEN, 16, 12)
        val dark = rgb(5, 5, 5)
        // A light sweeping across the first 12 frames keeps raising pixels (8 of 192 = 4 % a frame).
        for (f in 0 until 12) acc.add(IntArray(16 * 12) { i -> if (i % 16 == f && i / 16 < 8) rgb(200, 200, 200) else dark })
        assertFalse(acc.converged)
        // Then a static scene: nothing is raised, and the 5-frame window empties of the earlier raises.
        repeat(8) { acc.add(IntArray(16 * 12) { dark }) }
        assertTrue(acc.converged)
    }

    @Test
    fun `lighten ends the hold on an over-exposed stack`() {
        val acc = FrameAccumulator(StackBlend.LIGHTEN, 16, 12)
        // 5 % of the frame clipped to white from the start while pixels still get raised every frame.
        for (f in 0 until 9) acc.add(IntArray(16 * 12) { i -> if (i < 10) rgb(255, 255, 255) else rgb(10 + f * 20, 0, 0) })
        assertTrue(acc.converged)
    }

    @Test
    fun `median converges when the median stops changing`() {
        val acc = FrameAccumulator(StackBlend.MEDIAN, 16, 12)
        repeat(20) { acc.add(IntArray(16 * 12) { rgb(100, 100, 100) }) }
        assertTrue(acc.converged)
    }

    @Test
    fun `temporal variance is zero where the scene is still and large where it flickers`() {
        val acc = FrameAccumulator(StackBlend.AVERAGE, 16, 12)
        repeat(10) { f ->
            acc.add(IntArray(16 * 12) { i -> if (i % 16 < 8) rgb(120, 120, 120) else if (f % 2 == 0) rgb(0, 0, 0) else rgb(255, 255, 255) })
        }
        val image = acc.result()!!
        val sd = image.temporalStdDev!!
        assertEquals(4, image.guideWidth)
        assertEquals(3, image.guideHeight)
        for (gy in 0 until 3) {
            assertEquals(0f, sd[gy * 4], 1e-4f)
            assertTrue(sd[gy * 4 + 3] > 0.3f)
        }
    }

    @Test
    fun `alignment samples each frame back by its content shift`() {
        val acc = FrameAccumulator(StackBlend.LIGHTEN, w, h, expectedFrames = 1, maxShiftPx = 3)
        val dark = rgb(0, 0, 0)
        val light = rgb(255, 255, 255)
        // The scene moved by (+3, +2): the dot seen at (5, 4) was at (2, 2) in the reference frame.
        acc.add(withDot(dark, light, 5, 4), shiftX = 3, shiftY = 2)
        val out = acc.result()!!.rgb
        assertEquals(light, out[2 * w + 2])
        assertEquals(dark, out[4 * w + 5])
    }

    @Test
    fun `shifts beyond the limit are clamped`() {
        val acc = FrameAccumulator(StackBlend.LIGHTEN, w, h, expectedFrames = 1, maxShiftPx = 1)
        acc.add(withDot(rgb(0, 0, 0), rgb(255, 255, 255), 5, 2), shiftX = 4, shiftY = 0)
        assertEquals(255, red(acc.result()!!.rgb[2 * w + 4]))
    }

    @Test
    fun `wrong-size frames are refused and an empty stack has no result`() {
        val acc = FrameAccumulator(StackBlend.AVERAGE, w, h, expectedFrames = 2)
        assertNull(acc.result())
        assertFalse(acc.add(IntArray(3)))
        assertNull(acc.result())
    }
}
