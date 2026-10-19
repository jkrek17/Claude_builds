package com.compositioncoach.vision.merge

import com.compositioncoach.vision.FrameAccumulator
import com.compositioncoach.vision.StackBlend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConvergenceTest {

    @Test
    fun `caps are 6 s for silk, 10 s for trails, 15 s for the crowd`() {
        assertEquals(6_000L, Convergence.capMs(StackBlend.AVERAGE))
        assertEquals(10_000L, Convergence.capMs(StackBlend.LIGHTEN))
        assertEquals(15_000L, Convergence.capMs(StackBlend.MEDIAN))
    }

    @Test
    fun `checkpoints double every two steps and every half is also a checkpoint`() {
        assertEquals(listOf(8, 12, 16, 24, 32, 48, 64, 96), (0 until 8).map { Convergence.checkpointFrames(it) })
        for (i in 2 until 12) assertEquals(Convergence.checkpointFrames(i), 2 * Convergence.checkpointFrames(i - 2))
    }

    @Test
    fun `the hold never stops before its minimum and always stops at the cap`() {
        // Converged at once, but the minimum is 4 s.
        assertFalse(Convergence.shouldStop(3_999, 4_000, 6_000, converged = true))
        assertTrue(Convergence.shouldStop(4_000, 4_000, 6_000, converged = true))
        // Never converging: runs on to the cap.
        assertFalse(Convergence.shouldStop(5_999, 4_000, 6_000, converged = false))
        assertTrue(Convergence.shouldStop(6_000, 4_000, 6_000, converged = false))
        // A minimum longer than the cap wins: the spec's hold time is a promise.
        assertFalse(Convergence.shouldStop(7_999, 8_000, 6_000, converged = false))
        assertTrue(Convergence.shouldStop(8_000, 8_000, 6_000, converged = false))
    }

    @Test
    fun `progress is the larger of time and convergence`() {
        assertEquals(0.25f, Convergence.holdProgress(1_000, 4_000, 0.1f), 1e-6f)
        assertEquals(0.6f, Convergence.holdProgress(1_000, 4_000, 0.6f), 1e-6f)
        assertEquals(1f, Convergence.holdProgress(9_000, 4_000, 0f), 0f)
    }

    @Test
    fun `thresholds follow the spec`() {
        assertTrue(Convergence.isAverageConverged(0.0099f))
        assertFalse(Convergence.isAverageConverged(0.01f))
        assertTrue(Convergence.isLightenConverged(raisedFraction = 0.0019f, clippedFraction = 0f))
        assertFalse(Convergence.isLightenConverged(raisedFraction = 0.002f, clippedFraction = 0.02f))
        assertTrue(Convergence.isLightenConverged(raisedFraction = 0.5f, clippedFraction = 0.0201f))
        assertTrue(Convergence.isMedianConverged(0.0049f))
        assertFalse(Convergence.isMedianConverged(0.005f))
    }

    @Test
    fun `mean change and changed fraction compare luma samples`() {
        val a = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
        val b = floatArrayOf(0.1f, 0.3f, 0.3f, 0.6f)
        assertEquals(0.075f, Convergence.meanAbsChange(a, b), 1e-6f)
        // Tolerance 12/255 = 0.047: the 0.1 and 0.2 steps count, the identical samples do not.
        assertEquals(0.5f, Convergence.changedFraction(a, b), 1e-6f)
        assertEquals(0f, Convergence.meanAbsChange(floatArrayOf(), floatArrayOf()), 0f)
    }

    @Test
    fun `closeness is one at the threshold and falls off beyond it`() {
        assertEquals(1f, Convergence.closeness(0.005f, 0.01f), 0f)
        assertEquals(0.5f, Convergence.closeness(0.02f, 0.01f), 1e-6f)
    }

    @Test
    fun `tracker needs three checkpoints before an average verdict and is then sticky`() {
        val tracker = ConvergenceTracker(StackBlend.AVERAGE)
        val flat = FloatArray(10) { 0.5f }
        assertFalse(tracker.checkpointDue(7))
        assertTrue(tracker.checkpointDue(8))
        tracker.onCheckpoint(flat)
        tracker.onCheckpoint(flat)
        assertFalse(tracker.converged)
        tracker.onCheckpoint(flat)
        assertTrue(tracker.converged)
        // A later, very different sample does not undo it.
        tracker.onCheckpoint(FloatArray(10) { 0.9f })
        assertTrue(tracker.converged)
        assertEquals(1f, tracker.estimate, 0f)
    }

    @Test
    fun `tracker estimate rises as the average settles`() {
        val tracker = ConvergenceTracker(StackBlend.AVERAGE)
        tracker.onCheckpoint(FloatArray(10) { 0.2f })
        tracker.onCheckpoint(FloatArray(10) { 0.3f })
        tracker.onCheckpoint(FloatArray(10) { 0.4f }) // 0.2 mean change against the first
        assertFalse(tracker.converged)
        assertEquals(0.05f, tracker.estimate, 1e-5f)
    }

    @Test
    fun `lighten has no checkpoints`() {
        assertFalse(ConvergenceTracker(StackBlend.LIGHTEN).checkpointDue(10_000))
    }

    @Test
    fun `a stream that settles converges after about the first comparison`() {
        val acc = FrameAccumulator(StackBlend.AVERAGE, 16, 12)
        var frames = 0
        while (!acc.converged && frames < 1_000) {
            acc.add(IntArray(16 * 12) { 0xFF000000.toInt() or 0x646464 })
            frames++
        }
        assertEquals(16, frames)
    }

    @Test
    fun `resolution policy needs eight frames in the first second`() {
        assertTrue(HoldResolution.sustains(8, 1_000))
        assertFalse(HoldResolution.sustains(7, 1_000))
        assertEquals(10f, HoldResolution.fps(15, 1_500), 1e-6f)
        assertEquals(0f, HoldResolution.fps(5, 0), 0f)
        val big = 1920L * 1440
        val small = 1280L * 960
        assertTrue(HoldResolution.advisesFallback(7.9f, big, small))
        assertFalse(HoldResolution.advisesFallback(8f, big, small))
        assertFalse(HoldResolution.advisesFallback(3f, small, small))
        assertFalse(HoldResolution.advisesFallback(null, big, small))
    }
}
