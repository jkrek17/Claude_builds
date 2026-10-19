package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.outcome.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/** The outcome strip's order, swipe arithmetic and plain words (replaces the old mode strip's test). */
class OutcomeStripLogicTest {

    @Test
    fun `the strip lists every outcome in the order PRODUCT_md gives`() {
        assertEquals(
            listOf("Anything", "A person", "A group", "A view", "Night", "Light trails", "Silky water", "Clear the crowd", "Food & things", "Selfie"),
            OutcomeStripLogic.outcomes.map { it.label },
        )
    }

    @Test
    fun `a swipe moves one outcome at a time`() {
        assertEquals(Outcome.PORTRAIT, OutcomeStripLogic.afterSteps(Outcome.ANYTHING, 1))
        assertEquals(Outcome.ANYTHING, OutcomeStripLogic.afterSteps(Outcome.PORTRAIT, -1))
    }

    @Test
    fun `the ends clamp rather than wrapping around`() {
        assertEquals(Outcome.ANYTHING, OutcomeStripLogic.afterSteps(Outcome.ANYTHING, -1))
        assertEquals(Outcome.SELFIE, OutcomeStripLogic.afterSteps(Outcome.FOOD, 1))
        assertEquals(Outcome.SELFIE, OutcomeStripLogic.afterSteps(Outcome.SELFIE, 1))
    }

    @Test
    fun `a drag left past the threshold advances, right goes back, short drags do nothing`() {
        assertEquals(1, OutcomeStripLogic.stepsForDrag(-60f, 48f))
        assertEquals(-1, OutcomeStripLogic.stepsForDrag(60f, 48f))
        assertEquals(0, OutcomeStripLogic.stepsForDrag(-20f, 48f))
        assertEquals(1, OutcomeStripLogic.stepsForDrag(-48f, 48f))
    }

    @Test
    fun `every outcome has its own label resource`() {
        val ids = Outcome.entries.map { OutcomeStripLogic.labelRes(it) }
        assertEquals(ids.size, ids.toSet().size)
    }
}
