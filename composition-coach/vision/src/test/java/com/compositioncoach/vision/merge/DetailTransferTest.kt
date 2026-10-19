package com.compositioncoach.vision.merge

import java.util.Random
import kotlin.math.abs
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailTransferTest {
    private val w = 64
    private val h = 16

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun level(c: Int) = c and 0xFF

    private fun step(low: Int, high: Int): IntArray = IntArray(w * h) { if (it % w < 32) grey(low) else grey(high) }

    /** A horizontal [1 2 1]/4 blur: what a slightly misaligned average does to an edge. */
    private fun blurred(src: IntArray): IntArray = IntArray(w * h) { i ->
        val x = i % w
        val y = i / w
        fun at(xx: Int) = level(src[y * w + xx.coerceIn(0, w - 1)])
        grey((at(x - 1) + 2 * at(x) + at(x + 1)) / 4)
    }

    private fun maxStep(px: IntArray, row: Int = 8): Int = (1 until w).maxOf { abs(level(px[row * w + it]) - level(px[row * w + it - 1])) }

    @Test
    fun `a blurred edge is steepened toward the reference`() {
        val reference = step(40, 200)
        val average = blurred(reference)
        val out = average.copyOf()
        DetailTransfer.apply(out, reference, 0, h, w, h, 0, h, noiseSigma = 0f)
        assertTrue("edge not steeper: ${maxStep(out)} vs ${maxStep(average)}", maxStep(out) > maxStep(average))
        var error = 0
        var errorBefore = 0
        for (x in 0 until w) {
            error += abs(level(out[8 * w + x]) - level(reference[8 * w + x]))
            errorBefore += abs(level(average[8 * w + x]) - level(reference[8 * w + x]))
        }
        assertTrue("closer to the reference: $error vs $errorBefore", error < errorBefore)
    }

    @Test
    fun `an edge gets no halo and flat areas are untouched`() {
        val reference = step(40, 200)
        val out = blurred(reference)
        DetailTransfer.apply(out, reference, 0, h, w, h, 0, h, noiseSigma = 0f)
        for (i in out.indices) assertTrue("overshoot at $i: ${level(out[i])}", level(out[i]) in 40..200)
        for (x in listOf(2, 10, 25, 40, 55, 62)) assertEquals(level(reference[8 * w + x]), level(out[8 * w + x]))
    }

    @Test
    fun `an edge that is already sharp is not overshot`() {
        val reference = step(40, 200)
        val out = reference.copyOf()
        DetailTransfer.apply(out, reference, 0, h, w, h, 0, h, noiseSigma = 0f)
        for (i in out.indices) assertEquals(level(reference[i]), level(out[i]))
    }

    @Test
    fun `the reference's noise is not added back to a clean average`() {
        val rng = Random(3)
        val reference = IntArray(w * h) { grey((100 + 4 * rng.nextGaussian()).toInt().coerceIn(0, 255)) }
        val sigma = DetailTransfer.estimateNoise(IntArray(w * h) { DetailTransfer.lumaOf(reference[it]) }, w, h)
        assertEquals(4f, sigma, 1.2f)
        val average = IntArray(w * h) { grey(100) }
        val out = average.copyOf()
        DetailTransfer.apply(out, reference, 0, h, w, h, 0, h, noiseSigma = sigma)
        var sq = 0.0
        for (i in out.indices) sq += (level(out[i]) - 100) * (level(out[i]) - 100)
        val rms = sqrt(sq / out.size)
        assertTrue("noise came back: rms $rms", rms < 0.8)
    }

    @Test
    fun `a strip with a halo gives the same result as the whole image`() {
        val reference = step(40, 200)
        val average = blurred(reference)
        val whole = average.copyOf()
        DetailTransfer.apply(whole, reference, 0, h, w, h, 0, h, noiseSigma = 0f)
        val band = average.copyOfRange(4 * w, 9 * w)
        val halo = reference.copyOfRange(3 * w, 10 * w)
        DetailTransfer.apply(band, halo, 3, 7, w, h, 4, 5, noiseSigma = 0f)
        for (i in band.indices) assertEquals(whole[4 * w + i], band[i])
    }
}
