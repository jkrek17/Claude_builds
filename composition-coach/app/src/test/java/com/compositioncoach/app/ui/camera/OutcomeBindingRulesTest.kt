package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.CaptureEnhancement.AUTO
import com.compositioncoach.app.camera.CaptureEnhancement.BOKEH
import com.compositioncoach.app.camera.CaptureEnhancement.HDR
import com.compositioncoach.app.camera.CaptureEnhancement.NIGHT
import com.compositioncoach.app.camera.CaptureEnhancement.OFF
import com.compositioncoach.app.camera.OutcomeCapturePlanner
import com.compositioncoach.app.camera.PipelineSupport
import com.compositioncoach.app.outcome.Outcome
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** What the outcome binding hands the controller, the degraded note it shows, and the hold chip's words. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OutcomeBindingRulesTest {

    private val full = PipelineSupport(motionStacking = true, focusStacking = true)
    private val none = PipelineSupport(motionStacking = false, focusStacking = false)

    @After
    fun restore() {
        GuidanceFormatter.strings = ChipStrings.English
    }

    @Test
    fun `the bind target is the override, else the persisted preference, resolved against the lens`() {
        assertEquals(NIGHT, OutcomeBindingRules.bindTarget(NIGHT, AUTO, setOf(OFF, AUTO, NIGHT)))
        assertEquals(AUTO, OutcomeBindingRules.bindTarget(null, AUTO, setOf(OFF, AUTO)))
        // A preference the lens lacks binds OFF, so an OFF override is "the same bind" and costs no rebind.
        assertEquals(OFF, OutcomeBindingRules.bindTarget(null, HDR, setOf(OFF, AUTO)))
        assertEquals(OFF, OutcomeBindingRules.bindTarget(BOKEH, AUTO, setOf(OFF)))
    }

    @Test
    fun `only Motion outcomes raise the analysis stream`() {
        assertEquals(CameraController.MOTION_ANALYSIS_RESOLUTION, OutcomeBindingRules.analysisResolutionHint(Outcome.SILKY_WATER))
        assertEquals(CameraController.MOTION_ANALYSIS_RESOLUTION, OutcomeBindingRules.analysisResolutionHint(Outcome.LIGHT_TRAILS))
        assertEquals(CameraController.MOTION_ANALYSIS_RESOLUTION, OutcomeBindingRules.analysisResolutionHint(Outcome.CLEAR_CROWD))
        assertNull(OutcomeBindingRules.analysisResolutionHint(Outcome.NIGHT))
        assertNull(OutcomeBindingRules.analysisResolutionHint(Outcome.ANYTHING))
    }

    @Test
    fun `every plan with an English note has a translatable note, and the other way round`() {
        val lenses = listOf(setOf(OFF), setOf(OFF, AUTO), setOf(OFF, AUTO, HDR, NIGHT, BOKEH))
        for (outcome in Outcome.entries) for (available in lenses) for (support in listOf(full, none)) {
            val plan = OutcomeCapturePlanner.plan(outcome, available, support)
            val note = DegradedNote.of(plan)
            assertEquals("$outcome on $available, $support", plan.degradedNote != null, note != null)
        }
    }

    @Test
    fun `the notes name what is missing`() {
        assertEquals(DegradedNote.NO_NIGHT, DegradedNote.of(OutcomeCapturePlanner.plan(Outcome.NIGHT, setOf(OFF, AUTO), full)))
        assertEquals(DegradedNote.NO_PORTRAIT_BLUR, DegradedNote.of(OutcomeCapturePlanner.plan(Outcome.PORTRAIT, setOf(OFF), full)))
        assertEquals(DegradedNote.NO_HDR, DegradedNote.of(OutcomeCapturePlanner.plan(Outcome.LANDSCAPE, setOf(OFF), none)))
        assertEquals(DegradedNote.NO_MOTION, DegradedNote.of(OutcomeCapturePlanner.plan(Outcome.SILKY_WATER, setOf(OFF), none)))
        assertNull(DegradedNote.of(OutcomeCapturePlanner.plan(Outcome.SILKY_WATER, setOf(OFF), full)))
        assertNull(DegradedNote.of(OutcomeCapturePlanner.plan(Outcome.ANYTHING, setOf(OFF), none)))
    }

    @Test
    fun `degraded notes and the too-shaky notice resolve from strings xml`() {
        val resources = RuntimeEnvironment.getApplication().resources
        DegradedNote.entries.forEach { assertNotNull(resources.getString(it.resId)) }
        assertEquals("Too shaky, try again", resources.getString(CameraNotice.TOO_SHAKY.resId))
        assertEquals(
            "Night isn't available on this phone, using the best we can",
            resources.getString(DegradedNote.NO_NIGHT.resId),
        )
    }

    @Test
    fun `the hold chip comes from the chip table, so it can be translated`() {
        assertEquals("Hold still", HoldCues.text(Outcome.NIGHT, "fallback"))
        assertEquals("Hold still for 4 seconds", HoldCues.text(Outcome.LIGHT_TRAILS, "fallback"))
        assertEquals("Hold still for 3 seconds", HoldCues.text(Outcome.SILKY_WATER, "fallback"))
        assertEquals("Hold still for 5 seconds", HoldCues.text(Outcome.CLEAR_CROWD, "fallback"))
        // The English chip table matches each outcome's own instruction.
        Outcome.entries.filter { it.needsHold }.forEach { assertEquals(it.spec.instruction, HoldCues.text(it, "?")) }
        GuidanceFormatter.strings = ChipStrings { "x-${it.name}" }
        assertEquals("x-HOLD_STILL", HoldCues.text(Outcome.NIGHT, "fallback"))
        val holding = CameraUiState().withOutcome(Outcome.NIGHT).withCaptureStarted()
        assertEquals("x-HOLD_STILL", LiveChipRules.overrideChip(holding)?.text)
    }
}
