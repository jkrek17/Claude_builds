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
    fun `night uses the extension when there is one, else stacks eight frames itself and says nothing`() {
        val ext = OutcomeCapturePlanner.plan(Outcome.NIGHT, setOf(OFF, AUTO, NIGHT), full)
        assertEquals(NIGHT, ext.enhancement)
        assertEquals(Pipeline.SINGLE, ext.pipeline)
        assertEquals(Pipeline.NIGHT_STACK, ext.requested)
        assertFalse(ext.degraded)
        for (available in listOf(setOf(OFF), setOf(OFF, AUTO), setOf(OFF, AUTO, HDR, BOKEH))) {
            val plan = OutcomeCapturePlanner.plan(Outcome.NIGHT, available, full)
            // The stack needs the plain session, so even an AUTO extension is not bound for it.
            assertEquals(OFF, plan.enhancement)
            assertEquals(Pipeline.NIGHT_STACK, plan.pipeline)
            assertNull(plan.missingEnhancement)
            assertNull(plan.degradedNote)
        }
        assertEquals(2, Outcome.NIGHT.spec.holdSeconds)
    }

    @Test
    fun `the everyday outcomes merge a burst, and the extension replaces the merge silently`() {
        for (outcome in listOf(Outcome.ANYTHING, Outcome.PORTRAIT, Outcome.SELFIE)) {
            val plain = OutcomeCapturePlanner.plan(outcome, setOf(OFF), full)
            assertEquals("$outcome", Pipeline.BURST_MERGE, plain.pipeline)
            assertEquals("$outcome", Pipeline.BURST_MERGE, plain.requested)
        }
        // The vendor's AUTO never displaces the merge: the merge is the outcome's point.
        val auto = OutcomeCapturePlanner.plan(Outcome.ANYTHING, setOf(OFF, AUTO), full)
        assertEquals(OFF, auto.enhancement)
        assertEquals(Pipeline.BURST_MERGE, auto.pipeline)
        assertFalse(auto.degraded)
        // A named extension still wins for the outcomes that ask for one (true depth bokeh beats a mask blur).
        val bokeh = OutcomeCapturePlanner.plan(Outcome.PORTRAIT, setOf(OFF, AUTO, BOKEH), full)
        assertEquals(BOKEH, bokeh.enhancement)
        assertEquals(Pipeline.SINGLE, bokeh.pipeline)
    }

    @Test
    fun `portrait uses the vendor bokeh when offered and skips the merge under it`() {
        val plan = OutcomeCapturePlanner.plan(Outcome.PORTRAIT, setOf(OFF, BOKEH), full)
        assertEquals(BOKEH, plan.enhancement)
        assertEquals(Pipeline.SINGLE, plan.pipeline)
        assertFalse(plan.degraded)
        val without = OutcomeCapturePlanner.plan(Outcome.PORTRAIT, setOf(OFF), full)
        assertEquals(OFF, without.enhancement)
        assertEquals(Pipeline.BURST_MERGE, without.pipeline)
        assertEquals(BOKEH, without.missingEnhancement)
    }

    @Test
    fun `a group composites faces on the plain session`() {
        val plan = OutcomeCapturePlanner.plan(Outcome.GROUP, setOf(OFF), none)
        assertEquals(Pipeline.GROUP_COMPOSITE, plan.pipeline)
        assertFalse(plan.degraded)
        val withAuto = OutcomeCapturePlanner.plan(Outcome.GROUP, setOf(OFF, AUTO), none)
        assertEquals(OFF, withAuto.enhancement)
        assertEquals(Pipeline.GROUP_COMPOSITE, withAuto.pipeline)
    }

    @Test
    fun `a view brackets exposures on the plain session whatever the lens offers`() {
        // The bracket (with the focus stack inside) beats AUTO and HDR: no vendor session can carry it.
        for (support in listOf(full, none)) for (available in listOf(setOf(OFF), setOf(OFF, AUTO), setOf(OFF, HDR), setOf(OFF, AUTO, HDR))) {
            val plan = OutcomeCapturePlanner.plan(Outcome.LANDSCAPE, available, support)
            assertEquals("$available, $support", OFF, plan.enhancement)
            assertEquals("$available, $support", Pipeline.EXPOSURE_BRACKET, plan.pipeline)
            // A missing HDR extension is not a degradation: the standard pipeline is HDR on such phones (Pixels).
            assertNull(plan.missingEnhancement)
            assertFalse(plan.degraded)
        }
    }

    @Test
    fun `food focus stacks at the near distances when the lens can, else merges a burst, without a note`() {
        val stacked = OutcomeCapturePlanner.plan(Outcome.FOOD, setOf(OFF, AUTO), full)
        assertEquals(Pipeline.FOCUS_STACK, stacked.pipeline)
        assertEquals(OFF, stacked.enhancement)
        assertFalse(stacked.degraded)
        val noFocus = OutcomeCapturePlanner.plan(Outcome.FOOD, setOf(OFF), none)
        assertEquals(Pipeline.BURST_MERGE, noFocus.pipeline)
        assertEquals(Pipeline.FOCUS_STACK, noFocus.requested)
        assertNull(noFocus.degradedNote)
        // Without manual focus an AUTO extension is still the better session for food.
        val ext = OutcomeCapturePlanner.plan(Outcome.FOOD, setOf(OFF, AUTO), none)
        assertEquals(AUTO, ext.enhancement)
        assertEquals(Pipeline.SINGLE, ext.pipeline)
    }

    @Test
    fun `merge pipelines are flagged for the settings switch`() {
        val merges = Pipeline.entries.filter { it.isMerge }.toSet()
        assertEquals(setOf(Pipeline.BURST_MERGE, Pipeline.NIGHT_STACK, Pipeline.EXPOSURE_BRACKET, Pipeline.GROUP_COMPOSITE), merges)
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
        assertEquals(Pipeline.BURST_MERGE, plan.pipeline)
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

    @Test
    fun `a missing HDR extension is never reported for any outcome or lens`() {
        val lenses = listOf(setOf(OFF), setOf(OFF, AUTO), setOf(OFF, NIGHT, BOKEH), setOf(OFF, AUTO, NIGHT, BOKEH))
        for (outcome in Outcome.entries) for (available in lenses) for (support in listOf(full, none)) {
            val plan = OutcomeCapturePlanner.plan(outcome, available, support)
            assertFalse("$outcome on $available, $support", plan.missingEnhancement == HDR)
            assertFalse("$outcome on $available, $support", plan.degradedNote?.contains("HDR") == true)
        }
    }

    @Test
    fun `a note appears only when the plan differs from what the outcome asked for`() {
        val lenses = listOf(setOf(OFF), setOf(OFF, AUTO), setOf(OFF, HDR), setOf(OFF, AUTO, HDR, NIGHT, BOKEH))
        for (outcome in Outcome.entries) for (available in lenses) for (support in listOf(full, none)) {
            val plan = OutcomeCapturePlanner.plan(outcome, available, support)
            val first = outcome.spec.enhancementPreference.firstOrNull() ?: OFF
            val enhancementShortfall = plan.missingEnhancement != null
            val pipelineShortfall = plan.pipeline != plan.requested && plan.enhancement == OFF
            val label = "$outcome on $available, $support -> $plan"
            if (plan.degraded) assertTrue(label, enhancementShortfall || pipelineShortfall)
            // Getting the first choice, or a silent fallback (OFF, AUTO, HDR), with the requested pipeline: no note.
            if (plan.enhancement == first && plan.pipeline == plan.requested) assertFalse(label, plan.degraded)
            if (first in setOf(OFF, AUTO, HDR) && plan.pipeline == plan.requested) assertFalse(label, plan.degraded)
        }
    }
}
