package com.compositioncoach.app.camera.merge

import com.compositioncoach.vision.PerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureBracketCaptureTest {

    @Test
    fun `a third-stop lens brackets six steps either side of the base`() {
        assertEquals(listOf(0, -6, 6), ExposureBracketCapture.bracketIndices(base = 0, stepEv = 1f / 3f, lower = -12, upper = 12))
    }

    @Test
    fun `the bracket is relative to the exposure compensation the user set`() {
        assertEquals(listOf(2, -4, 8), ExposureBracketCapture.bracketIndices(base = 2, stepEv = 1f / 3f, lower = -12, upper = 12))
    }

    @Test
    fun `a narrower range uses its ends`() {
        assertEquals(listOf(0, -3, 3), ExposureBracketCapture.bracketIndices(base = 0, stepEv = 0.5f, lower = -3, upper = 3))
        assertEquals(listOf(0, -1, 1), ExposureBracketCapture.bracketIndices(base = 0, stepEv = 1f, lower = -1, upper = 1))
    }

    @Test
    fun `a base at one end of the range drops the frame that would repeat it`() {
        assertEquals(listOf(-12, -6), ExposureBracketCapture.bracketIndices(base = -12, stepEv = 1f / 3f, lower = -12, upper = 12))
        assertEquals(listOf(12, 6), ExposureBracketCapture.bracketIndices(base = 12, stepEv = 1f / 3f, lower = -12, upper = 12))
    }

    @Test
    fun `a full tier with a focus-stackable lens takes five frames`() {
        val plan = ExposureBracketCapture.plan(PerformanceTier.FULL, 0, 1f / 3f, -12, 12, canFocusStack = true)
        assertTrue(plan.focusStack)
        assertEquals(3, plan.exposureIndices.size)
        assertEquals(5, plan.frameCount)
    }

    @Test
    fun `a full tier without focus stacking takes the three exposures`() {
        val plan = ExposureBracketCapture.plan(PerformanceTier.FULL, 0, 1f / 3f, -12, 12, canFocusStack = false)
        assertFalse(plan.focusStack)
        assertEquals(3, plan.frameCount)
    }

    @Test
    fun `reduced halves the frames and keeps the dark one`() {
        val plan = ExposureBracketCapture.plan(PerformanceTier.REDUCED, 0, 1f / 3f, -12, 12, canFocusStack = true)
        assertFalse(plan.focusStack)
        assertEquals(listOf(0, -6), plan.exposureIndices)
    }

    @Test
    fun `minimal takes the first frame only`() {
        val plan = ExposureBracketCapture.plan(PerformanceTier.MINIMAL, 0, 1f / 3f, -12, 12, canFocusStack = true)
        assertFalse(plan.focusStack)
        assertEquals(listOf(0), plan.exposureIndices)
        assertEquals(1, plan.frameCount)
    }
}
