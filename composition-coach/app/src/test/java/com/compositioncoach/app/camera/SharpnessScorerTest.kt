package com.compositioncoach.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharpnessScorerTest {

    private val width = 96
    private val height = 64

    /** A vertical step edge (dark left, bright right) plus a few horizontal bars, i.e. a "scene" with detail. */
    private fun sharpScene(): IntArray = IntArray(width * height) { i ->
        val x = i % width
        val y = i / width
        val edge = if (x < width / 2) 40 else 210
        if (y % 16 < 2) 255 - edge else edge
    }

    /** Separable box blur of radius [r], the stand-in for hand-shake/defocus blur. */
    private fun boxBlur(src: IntArray, r: Int): IntArray {
        val tmp = IntArray(src.size)
        val out = IntArray(src.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0
            var n = 0
            for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, width - 1)
                sum += src[y * width + xx]; n++
            }
            tmp[y * width + x] = sum / n
        }
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0
            var n = 0
            for (dy in -r..r) {
                val yy = (y + dy).coerceIn(0, height - 1)
                sum += tmp[yy * width + x]; n++
            }
            out[y * width + x] = sum / n
        }
        return out
    }

    @Test
    fun `a sharp edge scores higher than the same edge blurred`() {
        val sharp = sharpScene()
        val blurred = boxBlur(sharp, 2)
        val sharpScore = SharpnessScorer.score(sharp, width, height)
        val blurredScore = SharpnessScorer.score(blurred, width, height)
        assertTrue("sharp=$sharpScore blurred=$blurredScore", sharpScore > blurredScore)
    }

    @Test
    fun `more blur scores monotonically lower`() {
        val sharp = sharpScene()
        val scores = listOf(0, 1, 2, 4).map { r -> SharpnessScorer.score(if (r == 0) sharp else boxBlur(sharp, r), width, height) }
        for (i in 1 until scores.size) assertTrue("scores=$scores", scores[i] < scores[i - 1])
    }

    @Test
    fun `sharpness survives the box downscale used on real frames`() {
        val sharp = sharpScene()
        val blurred = boxBlur(sharp, 3)
        assertTrue(
            SharpnessScorer.score(sharp, width, height, downscaleFactor = 2) >
                SharpnessScorer.score(blurred, width, height, downscaleFactor = 2),
        )
    }

    @Test
    fun `a flat plane has zero Laplacian variance`() {
        val flat = IntArray(width * height) { 128 }
        assertEquals(0.0, SharpnessScorer.varianceOfLaplacian(flat, width, height), 1e-9)
    }

    @Test
    fun `sharpestIndex picks the highest score and the earliest on a tie`() {
        assertEquals(1, SharpnessScorer.sharpestIndex(listOf(1f, 5f, 3f)))
        assertEquals(0, SharpnessScorer.sharpestIndex(listOf(4f, 4f, 2f)))
        assertEquals(-1, SharpnessScorer.sharpestIndex(emptyList()))
    }

    @Test
    fun `downscale averages blocks and drops the remainder`() {
        val plane = intArrayOf(
            0, 4, 8, 9,
            4, 8, 8, 9,
            1, 1, 1, 9,
        )
        val out = SharpnessScorer.downscale(plane, 4, 3, 2)
        assertEquals(2, out.width)
        assertEquals(1, out.height)
        assertEquals(4, out.luma[0]) // (0+4+4+8)/4
        assertEquals(8, out.luma[1]) // (8+9+8+9)/4 = 8.5 -> 8
    }

    @Test
    fun `luma weights green most and keeps grey grey`() {
        val luma = SharpnessScorer.lumaFromArgb(intArrayOf(0xFF808080.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()))
        assertEquals(128, luma[0], 1)
        assertTrue(luma[1] > luma[2])
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Int) {
        assertTrue("expected $expected±$tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
    }
}
