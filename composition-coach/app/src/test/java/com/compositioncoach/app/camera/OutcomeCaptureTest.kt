package com.compositioncoach.app.camera

import com.compositioncoach.app.camera.CaptureEnhancement.AUTO
import com.compositioncoach.app.camera.CaptureEnhancement.BOKEH
import com.compositioncoach.app.camera.CaptureEnhancement.HDR
import com.compositioncoach.app.camera.CaptureEnhancement.NIGHT
import com.compositioncoach.app.camera.CaptureEnhancement.OFF
import com.compositioncoach.app.outcome.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.compositioncoach.app.outcome.CapturePipeline as Pipeline

/** Outcome planning, BOKEH availability, gyro integration and the additive diagnostics. */
class OutcomeCaptureTest {

    private val full = PipelineSupport(motionStacking = true, focusStacking = true)
    private val none = PipelineSupport(motionStacking = false, focusStacking = false)

    @Test
    fun `night falls back to the next available enhancement and says so once`() {
        val plan = OutcomeCapturePlanner.plan(Outcome.NIGHT, setOf(OFF, AUTO), full)
        assertEquals(AUTO, plan.enhancement)
        assertEquals(NIGHT, plan.missingEnhancement)
        assertEquals("Night isn't available on this phone, using the best we can", plan.degradedNote)
        assertEquals(Pipeline.SINGLE, plan.pipeline)
    }

    @Test
    fun `portrait uses the vendor bokeh when offered and skips lucky imaging under it`() {
        val plan = OutcomeCapturePlanner.plan(Outcome.PORTRAIT, setOf(OFF, BOKEH), full)
        assertEquals(BOKEH, plan.enhancement)
        assertEquals(Pipeline.SINGLE, plan.pipeline)
        assertFalse(plan.degraded)
        val without = OutcomeCapturePlanner.plan(Outcome.PORTRAIT, setOf(OFF), full)
        assertEquals(OFF, without.enhancement)
        assertEquals(Pipeline.BURST_SHARPEST, without.pipeline)
        assertEquals(BOKEH, without.missingEnhancement)
    }

    @Test
    fun `landscape focus stacks only without an extension and with manual focus`() {
        assertEquals(Pipeline.SINGLE, OutcomeCapturePlanner.plan(Outcome.LANDSCAPE, setOf(OFF, HDR), full).pipeline)
        val stacked = OutcomeCapturePlanner.plan(Outcome.LANDSCAPE, setOf(OFF), full)
        assertEquals(Pipeline.FOCUS_STACK, stacked.pipeline)
        assertEquals(HDR, stacked.missingEnhancement)
        val noFocus = OutcomeCapturePlanner.plan(Outcome.LANDSCAPE, setOf(OFF), none)
        assertEquals(Pipeline.BURST_SHARPEST, noFocus.pipeline)
        // The missing first-choice enhancement is the note the user sees (one note, not two).
        assertEquals(OutcomeCapturePlanner.enhancementNote(HDR), noFocus.degradedNote)
        val noFocusNoHdrWanted = OutcomeCapturePlanner.plan(Outcome.LANDSCAPE, setOf(OFF, HDR), none)
        assertEquals(Pipeline.SINGLE, noFocusNoHdrWanted.pipeline)
    }

    @Test
    fun `the focus stack beats AUTO but not a named HDR`() {
        val plan = OutcomeCapturePlanner.plan(Outcome.LANDSCAPE, setOf(OFF, AUTO), full)
        assertEquals(OFF, plan.enhancement)
        assertEquals(Pipeline.FOCUS_STACK, plan.pipeline)
        assertEquals(HDR, OutcomeCapturePlanner.plan(Outcome.LANDSCAPE, setOf(OFF, AUTO, HDR), full).enhancement)
        assertEquals(AUTO, OutcomeCapturePlanner.plan(Outcome.LANDSCAPE, setOf(OFF, AUTO), none).enhancement)
    }

    @Test
    fun `motion outcomes need live frames`() {
        assertEquals(Pipeline.MOTION_LIGHTEN, OutcomeCapturePlanner.plan(Outcome.LIGHT_TRAILS, setOf(OFF), full).pipeline)
        assertEquals(Pipeline.MOTION_MEDIAN, OutcomeCapturePlanner.plan(Outcome.CLEAR_CROWD, setOf(OFF), full).pipeline)
        val fallback = OutcomeCapturePlanner.plan(Outcome.SILKY_WATER, setOf(OFF), none)
        assertEquals(Pipeline.BURST_SHARPEST, fallback.pipeline)
        assertEquals(OutcomeCapturePlanner.MOTION_UNAVAILABLE, fallback.degradedNote)
    }

    @Test
    fun `a missing AUTO is not a degradation`() {
        val plan = OutcomeCapturePlanner.plan(Outcome.ANYTHING, setOf(OFF), full)
        assertEquals(OFF, plan.enhancement)
        assertNull(plan.degradedNote)
        assertEquals(Pipeline.BURST_SHARPEST, plan.pipeline)
    }

    @Test
    fun `bokeh is offered like HDR and Night, even when it pauses coaching`() {
        val offered = CaptureQuality.offeredEnhancements(setOf(BOKEH, NIGHT), analysisSupported = emptySet(), needsAnalysis = true)
        assertEquals(setOf(OFF, NIGHT, BOKEH), offered)
    }

    @Test
    fun `gyro history integrates rate over the asked interval`() {
        val history = GyroHistory()
        for (i in 0..100) history.add(i * 10_000_000L, 0.1f, -0.2f, 0f) // 1 s at 100 Hz
        val d = history.deltaBetween(0L, 1_000_000_000L)!!
        assertEquals(0.1f, d.x, 1e-4f)
        assertEquals(-0.2f, d.y, 1e-4f)
        val half = history.deltaBetween(500_000_000L, 1_000_000_000L)!!
        assertEquals(0.05f, half.x, 1e-4f)
        val back = history.deltaBetween(1_000_000_000L, 500_000_000L)!!
        assertEquals(-0.05f, back.x, 1e-4f)
        assertNull(history.deltaBetween(-1L, 10L))
        assertNull(GyroHistory().apply { add(0, 1f, 1f, 1f) }.deltaBetween(0, 0))
    }

    @Test
    fun `gyro history is bounded`() {
        val history = GyroHistory(capacity = 10)
        for (i in 0 until 50) history.add(i * 10_000_000L, 1f, 0f, 0f)
        assertNull(history.deltaBetween(0L, 490_000_000L))
        assertEquals(0.08f, history.deltaBetween(410_000_000L, 490_000_000L)!!.x, 1e-4f)
    }

    @Test
    fun `diagnostics log the pipeline only when there is one`() {
        assertFalse(CaptureDiagnostics().toLogLine().contains("pipeline="))
        val line = CaptureDiagnostics(pipeline = "MOTION_AVERAGE", framesStacked = 31, degradedNote = "Too shaky, try again").toLogLine()
        assertTrue(line.endsWith(" pipeline=MOTION_AVERAGE stacked=31 degraded=\"Too shaky, try again\""))
    }

    @Test
    fun `motion stacks are saved with a suffix per blend`() {
        assertEquals("_silk", MotionCapture.suffixFor(com.compositioncoach.vision.StackBlend.AVERAGE))
        assertEquals("_trails", MotionCapture.suffixFor(com.compositioncoach.vision.StackBlend.LIGHTEN))
        assertEquals("_clear", MotionCapture.suffixFor(com.compositioncoach.vision.StackBlend.MEDIAN))
    }
}
