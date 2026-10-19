package com.compositioncoach.app.outcome

import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.model.MetricCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OutcomePlannerTest {

    private val none = setOf(CaptureEnhancement.OFF)
    private val all = CaptureEnhancement.entries.toSet()

    @Test
    fun `the first preferred enhancement the lens offers wins`() {
        assertEquals(CaptureEnhancement.BOKEH, OutcomePlanner.plan(Outcome.PORTRAIT, all).enhancement)
        assertEquals(CaptureEnhancement.AUTO, OutcomePlanner.plan(Outcome.PORTRAIT, setOf(CaptureEnhancement.OFF, CaptureEnhancement.AUTO)).enhancement)
        assertEquals(CaptureEnhancement.OFF, OutcomePlanner.plan(Outcome.PORTRAIT, none).enhancement)
        assertEquals(CaptureEnhancement.NIGHT, OutcomePlanner.plan(Outcome.NIGHT, all).enhancement)
        assertEquals(CaptureEnhancement.OFF, OutcomePlanner.plan(Outcome.LIGHT_TRAILS, all).enhancement)
    }

    @Test
    fun `a missing first choice degrades the outcome with one note`() {
        val night = OutcomePlanner.plan(Outcome.NIGHT, setOf(CaptureEnhancement.OFF, CaptureEnhancement.AUTO))
        assertTrue(night.degraded)
        assertEquals("Night mode isn't available on this phone", night.note)
        val portrait = OutcomePlanner.plan(Outcome.PORTRAIT, none)
        assertTrue(portrait.degraded)
        assertEquals("This phone has no portrait mode; the background blur is added on review", portrait.note)
        assertTrue(OutcomePlanner.plan(Outcome.LANDSCAPE, none).degraded)
    }

    @Test
    fun `AUTO as first choice is never a promise, so its absence is not degraded`() {
        val anything = OutcomePlanner.plan(Outcome.ANYTHING, none)
        assertEquals(CaptureEnhancement.OFF, anything.enhancement)
        assertFalse(anything.degraded)
        assertNull(anything.note)
        assertFalse(OutcomePlanner.plan(Outcome.NIGHT, all).degraded)
    }

    @Test
    fun `an unavailable capture pipeline degrades the outcome`() {
        val motion = OutcomePlanner.plan(Outcome.SILKY_WATER, all) { it != CapturePipeline.MOTION_AVERAGE }
        assertTrue(motion.degraded)
        assertEquals("Silky water isn't available on this phone yet, so this takes a normal photo", motion.note)
        assertFalse(OutcomePlanner.plan(Outcome.SILKY_WATER, all) { true }.degraded)
    }

    @Test
    fun `an outcome's coaching focus is weighted up on top of the taste model`() {
        assertSame(AdaptiveWeights.NEUTRAL, OutcomePlanner.effectiveWeights(AdaptiveWeights.NEUTRAL, Outcome.ANYTHING))
        val user = AdaptiveWeights.of(mapOf(MetricCategory.LIGHT to 0.8f, MetricCategory.HORIZON to 0.7f))
        val portrait = OutcomePlanner.effectiveWeights(user, Outcome.PORTRAIT)
        assertEquals(0.8f * OutcomePlanner.FOCUS_BOOST, portrait.multiplier(MetricCategory.LIGHT), 1e-5f)
        assertEquals(OutcomePlanner.FOCUS_BOOST, portrait.multiplier(MetricCategory.HEADROOM), 1e-5f)
        assertEquals(0.7f, portrait.multiplier(MetricCategory.HORIZON), 1e-5f)
        val capped = OutcomePlanner.effectiveWeights(AdaptiveWeights.of(mapOf(MetricCategory.LIGHT to 1.4f)), Outcome.PORTRAIT)
        assertEquals(AdaptiveWeights.MAX_MULTIPLIER, capped.multiplier(MetricCategory.LIGHT), 1e-5f)
    }

    @Test
    fun `the hold instruction shows only while a hold outcome is pending`() {
        assertEquals("Hold still for 4 seconds", OutcomePlanner.holdInstruction(Outcome.LIGHT_TRAILS, holdPending = true))
        assertNull(OutcomePlanner.holdInstruction(Outcome.LIGHT_TRAILS, holdPending = false))
        assertNull(OutcomePlanner.holdInstruction(Outcome.PORTRAIT, holdPending = true))
        assertEquals("Hold still", OutcomePlanner.holdInstruction(Outcome.NIGHT, holdPending = true))
    }
}
