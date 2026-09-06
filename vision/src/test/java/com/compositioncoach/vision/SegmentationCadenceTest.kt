package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationCadenceTest {

    // --- base cadence (every 3rd accepted frame, subject present) ------------------------------------

    @Test
    fun `runs on every 3rd accepted frame when a subject is present`() {
        val cadence = SegmentationCadence()
        val ran = (1..9).map { cadence.onAcceptedFrame(hadSubjectLastFrame = true) }
        assertEquals(listOf(false, false, true, false, false, true, false, false, true), ran)
    }

    @Test
    fun `mask age resets to 0 on a frame that ran, then counts up`() {
        val cadence = SegmentationCadence()
        cadence.onAcceptedFrame(true) // frame 1: age -> 1
        assertEquals(1, cadence.maskAgeFrames)
        cadence.onAcceptedFrame(true) // frame 2: age -> 2
        assertEquals(2, cadence.maskAgeFrames)
        cadence.onAcceptedFrame(true) // frame 3: due, age resets -> 0
        assertEquals(0, cadence.maskAgeFrames)
        cadence.onAcceptedFrame(true) // frame 4: age -> 1
        assertEquals(1, cadence.maskAgeFrames)
    }

    // --- subject gating ------------------------------------------------------------------------------

    @Test
    fun `never runs when no subject was present last frame, regardless of cadence`() {
        val cadence = SegmentationCadence()
        val ran = (1..9).map { cadence.onAcceptedFrame(hadSubjectLastFrame = false) }
        assertTrue(ran.none { it })
    }

    @Test
    fun `mask age keeps growing while gated off (staleness is honest)`() {
        val cadence = SegmentationCadence()
        repeat(5) { cadence.onAcceptedFrame(hadSubjectLastFrame = false) }
        assertEquals(5, cadence.maskAgeFrames)
    }

    // --- perf ladder: degrade after 5 consecutive frames over 180ms ----------------------------------

    @Test
    fun `stays at interval 3 until 5 consecutive frames exceed 180ms`() {
        val cadence = SegmentationCadence()
        repeat(4) { cadence.recordLatency(200L) }
        assertEquals(3, cadence.intervalFrames)
        cadence.recordLatency(200L) // 5th consecutive slow frame
        assertEquals(6, cadence.intervalFrames)
    }

    @Test
    fun `a single fast frame in between resets the slow streak`() {
        val cadence = SegmentationCadence()
        // 4 slow frames, then one fast frame breaks the streak, then only 4 more slow frames -- without
        // the reset this would be 8 total slow samples (well past the threshold of 5 consecutive), but
        // since none of these post-reset runs reaches 5 *consecutive* frames, degrade must not trigger.
        repeat(4) { cadence.recordLatency(200L) }
        cadence.recordLatency(150L) // fast enough to reset the streak (not degraded, not yet recovered)
        repeat(4) { cadence.recordLatency(200L) }
        assertEquals("streak should have been broken by the 150ms frame", 3, cadence.intervalFrames)
    }

    @Test
    fun `recovers to interval 3 immediately on a single frame under 120ms`() {
        val cadence = SegmentationCadence()
        repeat(5) { cadence.recordLatency(200L) }
        assertEquals(6, cadence.intervalFrames)
        cadence.recordLatency(119L)
        assertEquals(3, cadence.intervalFrames)
    }

    @Test
    fun `latency between 120 and 180ms neither degrades nor recovers`() {
        val cadence = SegmentationCadence()
        repeat(10) { cadence.recordLatency(150L) }
        assertEquals(3, cadence.intervalFrames)
    }

    @Test
    fun `degraded cadence actually runs every 6th frame, not 3rd`() {
        val cadence = SegmentationCadence()
        repeat(5) { cadence.recordLatency(200L) } // degrade to interval 6
        assertEquals(6, cadence.intervalFrames)
        val ran = (1..12).map { cadence.onAcceptedFrame(hadSubjectLastFrame = true) }
        assertEquals(
            listOf(false, false, false, false, false, true, false, false, false, false, false, true),
            ran,
        )
    }

    @Test
    fun `fresh cadence starts at interval 3`() {
        assertEquals(3, SegmentationCadence().intervalFrames)
        assertEquals(0, SegmentationCadence().maskAgeFrames)
    }

    @Test
    fun `custom thresholds are honoured`() {
        val cadence = SegmentationCadence(
            normalIntervalFrames = 2,
            degradedIntervalFrames = 4,
            degradeLatencyMs = 100L,
            recoverLatencyMs = 50L,
            degradeStreakThreshold = 2,
        )
        assertEquals(2, cadence.intervalFrames)
        cadence.recordLatency(120L)
        cadence.recordLatency(120L)
        assertEquals(4, cadence.intervalFrames)
        cadence.recordLatency(40L)
        assertEquals(2, cadence.intervalFrames)
    }

    @Test
    fun `does not run when subject present but cadence not yet due`() {
        val cadence = SegmentationCadence()
        assertFalse(cadence.onAcceptedFrame(true))
        assertFalse(cadence.onAcceptedFrame(true))
    }
}
