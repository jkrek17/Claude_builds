package com.compositioncoach.vision.merge

import java.util.Random
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BurstAlignTest {
    private val w = 160
    private val h = 120

    /** A smooth textured scene with structure at several scales, evaluated anywhere (so shifts can be fractional). */
    private fun scene(x: Float, y: Float): Float =
        0.5f + 0.20f * sin(0.21f * x + 0.13f * y) + 0.15f * sin(0.07f * x - 0.31f * y + 1f) + 0.10f * sin(0.43f * x + 0.37f * y + 2f)

    private val reference = FloatArray(w * h) { scene((it % w).toFloat(), (it / w).toFloat()) }

    /** The reference's scene moved by (sx, sy): the frame's content is displaced, as after the hand moved. */
    private fun moved(sx: Float, sy: Float, noise: Float = 0f, seed: Long = 1): FloatArray {
        val rng = Random(seed)
        return FloatArray(w * h) { scene((it % w) - sx, (it / w) - sy) + noise * rng.nextGaussian().toFloat() }
    }

    private fun assertShift(expectedX: Float, expectedY: Float, actual: SubPixelShift, tolerance: Float = 0.3f) {
        assertEquals("dx", expectedX, actual.dx, tolerance)
        assertEquals("dy", expectedY, actual.dy, tolerance)
    }

    @Test
    fun `an identical frame needs no shift`() {
        assertShift(0f, 0f, BurstAlign.refine(reference, reference, w, h), 0.01f)
    }

    @Test
    fun `a known whole pixel shift is recovered from a cold start`() {
        assertShift(3f, -2f, BurstAlign.refine(reference, moved(3f, -2f), w, h))
        assertShift(-6f, 5f, BurstAlign.refine(reference, moved(-6f, 5f), w, h))
    }

    @Test
    fun `a known fractional shift is recovered to a fraction of a pixel`() {
        assertShift(2.5f, -1.5f, BurstAlign.refine(reference, moved(2.5f, -1.5f), w, h))
        assertShift(-0.7f, 1.3f, BurstAlign.refine(reference, moved(-0.7f, 1.3f), w, h))
    }

    @Test
    fun `a gyro guess a few pixels off is corrected`() {
        val actual = BurstAlign.refine(reference, moved(4f, 3f), w, h, initial = SubPixelShift(1f, 6f))
        assertShift(4f, 3f, actual)
    }

    @Test
    fun `noise does not move the answer`() {
        assertShift(3f, -2f, BurstAlign.refine(reference, moved(3f, -2f, noise = 0.03f), w, h), 0.4f)
    }

    @Test
    fun `a moving object does not drag the global shift`() {
        val frame = moved(3f, -2f)
        for (y in 40 until 80) for (x in 90 until 140) frame[y * w + x] = 0.95f
        assertShift(3f, -2f, BurstAlign.refine(reference, frame, w, h))
    }

    @Test
    fun `a flat frame keeps the gyro guess`() {
        val flat = FloatArray(w * h) { 0.3f }
        val actual = BurstAlign.refine(flat, flat, w, h, initial = SubPixelShift(2f, -1f))
        assertShift(2f, -1f, actual, 0.01f)
    }

    @Test
    fun `shifting a frame by the recovered amount lines it up with the reference`() {
        val frame = moved(2.5f, -1.5f)
        val found = BurstAlign.refine(reference, frame, w, h)
        val aligned = BurstAlign.shifted(frame, w, h, found)
        var error = 0f
        var n = 0
        for (y in 12 until h - 12) for (x in 12 until w - 12) {
            error += abs(aligned[y * w + x] - reference[y * w + x])
            n++
        }
        assertTrue("mean error ${error / n}", error / n < 0.02f)
    }

    @Test
    fun `shifting by nothing returns the guide itself`() {
        assertTrue(BurstAlign.shifted(reference, w, h, SubPixelShift.NONE) === reference)
    }

    @Test
    fun `sub pixel shifts convert to whole pixels at full resolution`() {
        val full = SubPixelShift(2.5f, -1.25f).scaled(4f).roundedToPixels()
        assertEquals(10, full.dx)
        assertEquals(-5, full.dy)
    }
}
