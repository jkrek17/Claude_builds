package com.compositioncoach.vision.merge

import com.compositioncoach.vision.PixelShift
import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionRejectedAverageTest {
    private val w = 128
    private val h = 96
    private val scale = 4

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun level(c: Int) = c and 0xFF

    private fun noisyFlat(value: Int, sigma: Double, seed: Long): IntArray {
        val rng = Random(seed)
        return IntArray(w * h) { grey((value + sigma * rng.nextGaussian()).toInt().coerceIn(0, 255)) }
    }

    private fun variance(px: IntArray, from: Int = 0, to: Int = px.size): Double {
        var sum = 0.0
        for (i in from until to) sum += level(px[i])
        val mean = sum / (to - from)
        var sq = 0.0
        for (i in from until to) sq += (level(px[i]) - mean) * (level(px[i]) - mean)
        return sq / (to - from)
    }

    /** The whole production path on small images: guides, weights, blend. */
    private fun merge(frames: List<IntArray>, referenceIndex: Int = 0): IntArray {
        val guides = frames.map { MotionRejectedAverage.lumaGuide(it, w, h, scale) }
        val gw = guides[0].second
        val gh = guides[0].third
        val weights = MotionRejectedAverage.weights(guides.map { it.first }, frames.map { SubPixelShift.NONE }, referenceIndex, gw, gh)
        return MotionRejectedAverage.blend(frames, w, h, weights, gw, gh)
    }

    @Test
    fun `merging N noisy frames of a flat field drops the noise variance by about 1 over N`() {
        for (n in intArrayOf(3, 5, 8)) {
            val frames = List(n) { noisyFlat(128, 8.0, seed = 10L + it) }
            val single = variance(frames[0])
            val merged = variance(merge(frames))
            val ratio = merged / single
            assertTrue("N=$n ratio $ratio", ratio in 0.75 / n..1.5 / n)
        }
    }

    @Test
    fun `a moving square is absent from the average`() {
        val n = 5
        val size = 20
        val positions = List(n) { 10 + it * 24 }
        val frames = List(n) { i ->
            val px = noisyFlat(80, 3.0, seed = 40L + i)
            for (y in 30 until 30 + size) for (x in positions[i] until positions[i] + size) px[y * w + x] = grey(200)
            px
        }
        val merged = merge(frames)
        // Where a later frame has the square but the reference does not: the reference's background, not a blend.
        for (i in 1 until n) {
            val cx = positions[i] + size / 2
            val v = level(merged[40 * w + cx])
            assertTrue("frame $i square leaked: $v", abs(v - 80) < 12)
        }
        // Where only the reference has it: still there at full strength.
        val ownCentre = level(merged[40 * w + positions[0] + size / 2])
        assertTrue("reference's square lost: $ownCentre", ownCentre > 185)
        // Far from any square the average still averages.
        val bgSingle = variance(frames[0], 70 * w, 90 * w)
        val bgMerged = variance(merged, 70 * w, 90 * w)
        assertTrue("background not averaged: $bgMerged vs $bgSingle", bgMerged < bgSingle * 0.45)
    }

    @Test
    fun `weights sum to one at every cell and the reference always counts`() {
        val frames = List(4) { noisyFlat(100, 5.0, seed = 70L + it) }
        val guides = frames.map { MotionRejectedAverage.lumaGuide(it, w, h, scale) }
        val gw = guides[0].second
        val gh = guides[0].third
        val weights = MotionRejectedAverage.weights(guides.map { it.first }, frames.map { SubPixelShift.NONE }, 2, gw, gh)
        for (i in 0 until gw * gh) {
            assertEquals(1f, weights.sumOf { it[i].toDouble() }.toFloat(), 1e-4f)
            assertTrue(weights[2][i] > 0f)
        }
    }

    @Test
    fun `a frame that disagrees everywhere has a tiny mean weight`() {
        val reference = MotionRejectedAverage.lumaGuide(noisyFlat(60, 2.0, 1), w, h, scale)
        val other = MotionRejectedAverage.lumaGuide(noisyFlat(200, 2.0, 2), w, h, scale)
        val raw = MotionRejectedAverage.rawWeights(reference.first, other.first, reference.second, reference.third)
        assertTrue(MotionRejectedAverage.meanWeight(raw) < MotionRejectedAverage.MIN_MEAN_WEIGHT)
        val same = MotionRejectedAverage.lumaGuide(noisyFlat(60, 2.0, 3), w, h, scale)
        val rawSame = MotionRejectedAverage.rawWeights(reference.first, same.first, reference.second, reference.third)
        assertTrue(MotionRejectedAverage.meanWeight(rawSame) > 0.6f)
    }

    @Test
    fun `the noise estimate is close to the true sigma`() {
        val rng = Random(5)
        val plane = FloatArray(200 * 150) { 0.5f + 0.02f * rng.nextGaussian().toFloat() }
        val sigma = MotionRejectedAverage.estimateNoise(plane, 200, 150)
        assertEquals(0.02f, sigma, 0.004f)
    }

    @Test
    fun `a texture does not inflate the noise estimate much`() {
        val rng = Random(6)
        val plane = FloatArray(200 * 150) { i ->
            val x = i % 200
            0.5f + (if ((x / 16) % 2 == 0) 0.2f else -0.2f) + 0.01f * rng.nextGaussian().toFloat()
        }
        assertTrue(MotionRejectedAverage.estimateNoise(plane, 200, 150) < 0.03f)
    }

    @Test
    fun `strips are sampled with their alignment shift`() {
        val ramp = IntArray(w * h) { grey(it % w) }
        val weights = listOf(FloatArray(4) { 1f })
        val out = IntArray(w * h)
        MotionRejectedAverage.blendStrip(
            listOf(MotionRejectedAverage.Strip(ramp, 0, h, PixelShift(5, 0))), w, h, 0, h, weights, 2, 2, out,
        )
        assertEquals(15, level(out[10]))
        // Clamped at the right edge.
        assertEquals(w - 1, level(out[w - 1]))
    }

    @Test
    fun `a strip blends the same rows the whole image does`() {
        val frames = List(3) { noisyFlat(100, 6.0, seed = 90L + it) }
        val guides = frames.map { MotionRejectedAverage.lumaGuide(it, w, h, scale) }
        val weights = MotionRejectedAverage.weights(guides.map { it.first }, frames.map { SubPixelShift.NONE }, 0, guides[0].second, guides[0].third)
        val whole = MotionRejectedAverage.blend(frames, w, h, weights, guides[0].second, guides[0].third)
        val band = IntArray(w * 20)
        val strips = frames.map { MotionRejectedAverage.Strip(it.copyOfRange(30 * w, 50 * w), 30, 20, PixelShift.NONE) }
        MotionRejectedAverage.blendStrip(strips, w, h, 30, 20, weights, guides[0].second, guides[0].third, band)
        for (i in band.indices) assertEquals(whole[30 * w + i], band[i])
    }
}
