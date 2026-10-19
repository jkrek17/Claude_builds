package com.compositioncoach.app.outcome

import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.model.MetricCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The state-layer outcome rules; the capture plan itself is `OutcomeCaptureTest`, its UI side `OutcomeBindingRulesTest`. */
class OutcomePlannerTest {

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
