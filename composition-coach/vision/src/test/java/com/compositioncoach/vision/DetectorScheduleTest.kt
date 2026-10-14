package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectorScheduleTest {

    @Test
    fun `FULL tier runs pose and objects on alternating frames, never the same one`() {
        val schedule = DetectorSchedule()
        val decisions = List(6) { schedule.onAcceptedFrame() }

        // Pose: frames 0, 2, 4. Objects: frames 1, 3, 5. Never both true together.
        assertEquals(listOf(true, false, true, false, true, false), decisions.map { it.runPose })
        assertEquals(listOf(false, true, false, true, false, true), decisions.map { it.runObjects })
        decisions.forEach { assertFalse(it.runPose && it.runObjects) }
    }

    @Test
    fun `objects are marked reusable for up to two frames after they last ran, then not`() {
        val schedule = DetectorSchedule()
        val f0 = schedule.onAcceptedFrame() // objects not due, nothing cached yet
        assertFalse(f0.reuseStaleObjects)
        val f1 = schedule.onAcceptedFrame() // objects due and run
        assertTrue(f1.runObjects)
        val f2 = schedule.onAcceptedFrame() // 1 frame stale: reusable
        assertFalse(f2.runObjects)
        assertTrue(f2.reuseStaleObjects)
        val f3 = schedule.onAcceptedFrame() // objects due again, run
        assertTrue(f3.runObjects)
    }

    @Test
    fun `REDUCED tier slows pose to every third frame and leaves objects unaffected`() {
        val schedule = DetectorSchedule()
        schedule.setTier(PerformanceTier.REDUCED)
        val decisions = List(6) { schedule.onAcceptedFrame() }

        assertEquals(listOf(true, false, false, true, false, false), decisions.map { it.runPose })
        assertEquals(listOf(false, true, false, true, false, true), decisions.map { it.runObjects })
        assertFalse(schedule.segmentationAllowedByTier())
    }

    @Test
    fun `MINIMAL tier turns pose and objects off entirely`() {
        val schedule = DetectorSchedule()
        schedule.setTier(PerformanceTier.MINIMAL)
        val decisions = List(4) { schedule.onAcceptedFrame() }

        decisions.forEach {
            assertFalse(it.runPose)
            assertFalse(it.runObjects)
            assertFalse(it.reuseStaleObjects)
        }
        assertFalse(schedule.segmentationAllowedByTier())
    }

    @Test
    fun `FULL tier allows segmentation`() {
        assertTrue(DetectorSchedule().segmentationAllowedByTier())
    }

    @Test
    fun `reset clears frame count and object staleness`() {
        val schedule = DetectorSchedule()
        schedule.onAcceptedFrame() // frame 0: pose
        schedule.onAcceptedFrame() // frame 1: objects run
        schedule.reset()

        val afterReset = schedule.onAcceptedFrame()
        // Back to frame 0 behaviour: pose runs, objects don't, and the pre-reset object result must
        // not be considered reusable any more (as if the pipeline had just started fresh).
        assertTrue(afterReset.runPose)
        assertFalse(afterReset.runObjects)
        assertFalse(afterReset.reuseStaleObjects)
    }

    @Test
    fun `switching tier takes effect on the very next frame`() {
        val schedule = DetectorSchedule()
        schedule.onAcceptedFrame() // frame 0 at FULL: pose runs
        schedule.setTier(PerformanceTier.MINIMAL)
        val next = schedule.onAcceptedFrame() // frame 1, now MINIMAL
        assertFalse(next.runPose)
        assertFalse(next.runObjects)
    }
}
