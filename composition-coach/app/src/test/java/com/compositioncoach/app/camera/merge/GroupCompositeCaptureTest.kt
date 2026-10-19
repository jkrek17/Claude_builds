package com.compositioncoach.app.camera.merge

import com.compositioncoach.vision.PerformanceTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCompositeCaptureTest {

    @Test
    fun `the burst length follows the performance tier`() {
        assertEquals(5, GroupCompositeCapture.frameCount(PerformanceTier.FULL))
        assertEquals(3, GroupCompositeCapture.frameCount(PerformanceTier.REDUCED))
        assertEquals(1, GroupCompositeCapture.frameCount(PerformanceTier.MINIMAL))
    }

    @Test
    fun `degraded notes are single plain sentences`() {
        val notes = listOf(
            GroupCompositeCapture.NOTE_ONE_FRAME,
            GroupCompositeCapture.NOTE_NO_FACES,
            GroupCompositeCapture.NOTE_DETECTION_FAILED,
            GroupCompositeCapture.NOTE_NO_IMPROVEMENT,
            GroupCompositeCapture.NOTE_MOVED,
            GroupCompositeCapture.NOTE_OUT_OF_MEMORY,
            GroupCompositeCapture.NOTE_MERGE_FAILED,
        )
        assertEquals(notes.size, notes.toSet().size)
        notes.forEach { assertTrue(it, it.endsWith(".") && it.count { c -> c == '.' } == 1) }
    }
}
