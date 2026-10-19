package com.compositioncoach.app.camera

import com.compositioncoach.vision.PixelShift
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FocusMergeTest {

    private val w = 128
    private val h = 64

    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    private fun value(c: Int) = c and 0xFF

    /** 16-px checkerboard: visible structure even on the 8x-smaller guide. */
    private val sharp = IntArray(w * h) { i -> if (((i % w) / 16 + (i / w) / 16) % 2 == 0) grey(230) else grey(25) }

    /** The same scene out of focus: a 17-px box blur in both directions. */
    private val blurred: IntArray = run {
        val r = 8
        val tmp = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (k in -r..r) s += value(sharp[y * w + (x + k).coerceIn(0, w - 1)])
            tmp[y * w + x] = s / (2 * r + 1)
        }
        IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            var s = 0
            for (k in -r..r) s += tmp[(y + k).coerceIn(0, h - 1) * w + x]
            grey(s / (2 * r + 1))
        }
    }

    /** Near-sharp on the left, far-sharp on the right, and the opposite. */
    private val nearSharp = IntArray(w * h) { i -> if (i % w < w / 2) sharp[i] else blurred[i] }
    private val farSharp = IntArray(w * h) { i -> if (i % w < w / 2) blurred[i] else sharp[i] }

    private fun meanError(px: IntArray, xs: IntRange): Double {
        var sum = 0.0
        var n = 0
        for (y in 0 until h) for (x in xs) {
            sum += abs(value(px[y * w + x]) - value(sharp[y * w + x]))
            n++
        }
        return sum / n
    }

    @Test
    fun `merge keeps the sharp half of each exposure`() {
        val guides = listOf(nearSharp, farSharp).map { FocusMerge.lumaGuide(it, w, h) }
        val (gw, gh) = guides[0].second to guides[0].third
        assertEquals(16, gw)
        assertEquals(8, gh)
        val weights = FocusMerge.weights(guides.map { it.first }, gw, gh)
        val merged = FocusMerge.blend(listOf(nearSharp, farSharp), w, h, weights, gw, gh)

        val left = 8 until 40
        val right = 88 until 120
        assertTrue("left half sharp: ${meanError(merged, left)}", meanError(merged, left) < 12)
        assertTrue("right half sharp: ${meanError(merged, right)}", meanError(merged, right) < 12)
        // Either source alone (or a plain average) is far off on one side.
        assertTrue(meanError(nearSharp, right) > 40)
        val average = IntArray(w * h) { grey((value(nearSharp[it]) + value(farSharp[it])) / 2) }
        assertTrue(meanError(average, left) > 20)
    }

    @Test
    fun `weights sum to one everywhere and split evenly on flat input`() {
        val flat = FloatArray(16 * 8) { 0.5f }
        val weights = FocusMerge.weights(listOf(flat, flat, flat), 16, 8)
        for (i in 0 until 16 * 8) {
            assertEquals(1f, weights.sumOf { it[i].toDouble() }.toFloat(), 1e-4f)
            assertEquals(1f / 3f, weights[0][i], 1e-4f)
        }
    }

    @Test
    fun `alignment shift samples the source where its content moved`() {
        val dot = IntArray(w * h) { grey(0) }.also { it[10 * w + 20] = grey(255) }
        val weights = listOf(FloatArray(16 * 8) { 1f })
        // Content moved by (+3, -2): the dot at (20, 10) was at (17, 12) in the reference.
        val out = FocusMerge.blend(listOf(dot), w, h, weights, 16, 8, listOf(PixelShift(3, -2)))
        assertEquals(255, value(out[12 * w + 17]))
        assertEquals(0, value(out[10 * w + 20]))
    }

    @Test
    fun `strips give the same result as a whole-image blend`() {
        val guides = listOf(nearSharp, farSharp).map { FocusMerge.lumaGuide(it, w, h).first }
        val weights = FocusMerge.weights(guides, 16, 8)
        val whole = FocusMerge.blend(listOf(nearSharp, farSharp), w, h, weights, 16, 8)
        val out = IntArray(w * h)
        val rows = 20
        var y0 = 0
        while (y0 < h) {
            val n = minOf(rows, h - y0)
            val strips = listOf(nearSharp, farSharp).map { src -> FocusMerge.Strip(src.copyOfRange(y0 * w, (y0 + n) * w), y0, n, PixelShift.NONE) }
            val part = IntArray(w * n)
            FocusMerge.blendStrip(strips, w, h, y0, n, weights, 16, 8, part)
            part.copyInto(out, y0 * w)
            y0 += n
        }
        assertTrue(whole.contentEquals(out))
    }
}
