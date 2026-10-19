package com.compositioncoach.vision.merge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MaskRefineTest {

    private val w = 80
    private val h = 40

    /** Luma step at x = 40: dark subject on the left (0.2), bright background on the right (0.8). */
    private val guide = FloatArray(w * h) { i -> if (i % w < 40) 0.2f else 0.8f }

    /** The mask wants the subject on the left but its edge is an 8-pixel linear ramp centred 6 pixels past the luma edge. */
    private val blurredMask = FloatArray(w * h) { i -> (0.5f - ((i % w) - 46f) / 8f).coerceIn(0f, 1f) }

    private fun row(a: FloatArray, y: Int = 20) = FloatArray(w) { a[y * w + it] }

    @Test
    fun `a blurred mask edge sharpens toward the luma edge`() {
        val refined = MaskRefine.refine(blurredMask, MaskRefine.Guide(guide, w, h))
        val before = row(blurredMask)
        val after = row(refined)

        // Where the subject (>= 0.8) ends: the input said x = 44, 4 pixels past the luma step at x = 40.
        fun subjectEnd(r: FloatArray) = r.indices.first { it > 20 && r[it] < 0.8f }
        assertEquals(44, subjectEnd(before))
        assertTrue("subject now ends at ${subjectEnd(after)}", abs(subjectEnd(after) - 40) <= 1)

        // The refined mask has a step across the luma edge where the input had none.
        assertTrue("step ${after[39] - after[40]}", after[39] - after[40] > 0.2f)
        assertEquals(0f, before[39] - before[40], 1e-6f)

        // Between the luma edge and the old mask edge the mask moved toward the background.
        for (x in 41..44) assertTrue("x=$x ${before[x]} -> ${after[x]}", after[x] < before[x] - 0.1f)
        // Deep inside the subject nothing changed.
        assertEquals(1f, after[30], 0.02f)
    }

    @Test
    fun `flat luma leaves the mask as soft as it was`() {
        val flat = FloatArray(w * h) { 0.5f }
        val refined = MaskRefine.refine(blurredMask, MaskRefine.Guide(flat, w, h))
        for (x in listOf(10, 14, 18, 46, 60, 64, 68)) assertEquals(blurredMask[20 * w + x], refined[20 * w + x], 0.1f)
    }

    @Test
    fun `edge refinement leaves a mask alone on flat luma and still snaps at an edge`() {
        val flat = FloatArray(w * h) { 0.5f }
        val unchanged = MaskRefine.refineEdges(blurredMask, MaskRefine.Guide(flat, w, h))
        for (i in unchanged.indices) assertEquals(blurredMask[i], unchanged[i], 1e-4f)

        val snapped = MaskRefine.refineEdges(blurredMask, MaskRefine.Guide(guide, w, h))
        assertTrue("step ${snapped[20 * w + 39] - snapped[20 * w + 40]}", snapped[20 * w + 39] - snapped[20 * w + 40] > 0.15f)
        for (x in 41..44) assertTrue(snapped[20 * w + x] < blurredMask[20 * w + x] - 0.1f)
    }

    @Test
    fun `a constant mask stays constant and values stay in range`() {
        val ones = FloatArray(w * h) { 1f }
        val refined = MaskRefine.refine(ones, MaskRefine.Guide(guide, w, h))
        assertTrue(refined.all { it in 0f..1f })
        assertEquals(1f, refined[20 * w + 40], 1e-3f)
    }

    @Test
    fun `box mean divides clamped windows by their true size`() {
        val src = FloatArray(5 * 1) { it.toFloat() } // 0 1 2 3 4
        val mean = MaskRefine.boxMean(src, 5, 1, 1)
        assertEquals(0.5f, mean[0], 1e-6f)
        assertEquals(1f, mean[1], 1e-6f)
        assertEquals(3.5f, mean[4], 1e-6f)
    }

    @Test
    fun `guide factor keeps the long edge near the guide size`() {
        assertEquals(1, MaskRefine.guideFactor(64, 48))
        assertEquals(1, MaskRefine.guideFactor(512, 400))
        assertEquals(2, MaskRefine.guideFactor(513, 400))
        assertEquals(8, MaskRefine.guideFactor(4000, 3000))
    }
}
