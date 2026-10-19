package com.compositioncoach.app.camera

import com.compositioncoach.composition.model.SubjectMask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class BackgroundSeparatorTest {

    private val w = 64
    private val h = 48

    /** Subject = grid cells 8..23 in both directions (a quarter of the frame), i.e. x 16..47, y 12..35. */
    private fun centreMask(): SubjectMask {
        val grid = FloatArray(32 * 32) { i -> if (i % 32 in 8..23 && i / 32 in 8..23) 1f else 0f }
        return SubjectMask(32, 32, grid)
    }

    private fun texture(): IntArray {
        val random = Random(7)
        return IntArray(w * h) {
            val v = 40 + random.nextInt(200)
            (0xFF shl 24) or (v shl 16) or ((255 - v) shl 8) or (v / 2)
        }
    }

    private fun luma(c: Int) = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF)

    @Test
    fun `subject pixels are untouched and background pixels are darkened and softened`() {
        val src = texture()
        val out = BackgroundSeparator.applyArgb(src, w, h, centreMask(), strength = 1f)
        for (y in 18 until 30) for (x in 22 until 42) assertEquals("subject ($x,$y)", src[y * w + x], out[y * w + x])

        val corners = listOf((0 until 10) to (0 until 8), (54 until 64) to (40 until 48))
        var before = 0.0
        var after = 0.0
        var changed = 0
        var n = 0
        for ((xs, ys) in corners) for (y in ys) for (x in xs) {
            val i = y * w + x
            before += luma(src[i])
            after += luma(out[i])
            if (src[i] != out[i]) changed++
            n++
        }
        assertTrue("most background pixels change", changed > n * 0.9)
        // Darkened by 0.75 (the blur keeps the mean): allow for the texture's local mean varying.
        assertEquals(0.75, after / before, 0.08)
        // Softened: neighbouring background pixels differ much less than in the noisy source.
        fun roughness(px: IntArray) = (1 until 10).sumOf { x -> kotlin.math.abs(luma(px[2 * w + x]) - luma(px[2 * w + x - 1])) }
        assertTrue(roughness(out) < roughness(src) / 3)
    }

    @Test
    fun `zero strength is the identity`() {
        val src = texture()
        assertTrue(src.contentEquals(BackgroundSeparator.applyArgb(src, w, h, centreMask(), strength = 0f)))
    }

    @Test
    fun `the edge is feathered, not cut`() {
        val src = IntArray(w * h) { (0xFF shl 24) or 0xC8C8C8 }
        val out = BackgroundSeparator.applyArgb(src, w, h, centreMask(), 1f)
        // Walking out of the subject along the middle row, brightness falls monotonically through intermediate values.
        val row = 24
        val values = (6 until 26).map { x -> luma(out[row * w + x]) }
        assertTrue(values.zipWithNext().all { (a, b) -> b >= a - 0.5 })
        assertTrue(values.any { it > 155 && it < 195 })
        assertNotEquals(values.first(), values.last())
    }

    @Test
    fun `offered only for 8 to 70 percent coverage`() {
        assertTrue(BackgroundSeparator.isApplicable(centreMask()))
        assertFalse(BackgroundSeparator.isApplicable(SubjectMask(32, 32, FloatArray(32 * 32) { 1f })))
        assertFalse(BackgroundSeparator.isApplicable(SubjectMask(32, 32, FloatArray(32 * 32) { if (it < 40) 1f else 0f })))
        assertFalse(BackgroundSeparator.isApplicable(null))
    }
}
