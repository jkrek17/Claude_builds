package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.SceneIntent
import org.junit.Assert.assertEquals
import org.junit.Test

/** The mode strip's swipe arithmetic: one mode per swipe, clamped at both ends, in the spec's order. */
class ModeStripLogicTest {

    @Test
    fun `the strip lists the modes in the order the spec gives`() {
        assertEquals(
            listOf("Auto", "Portrait", "Group", "Landscape", "Architecture", "Object / food"),
            ModeStripLogic.modes.map { it.label },
        )
    }

    @Test
    fun `a swipe moves one mode at a time`() {
        assertEquals(SceneIntent.PORTRAIT, ModeStripLogic.modeAfterSteps(SceneIntent.AUTO, 1))
        assertEquals(SceneIntent.AUTO, ModeStripLogic.modeAfterSteps(SceneIntent.PORTRAIT, -1))
    }

    @Test
    fun `the ends clamp rather than wrapping around`() {
        // Wrapping would make a hard swipe at either end jump the whole way across the strip, which is
        // not how a camera's mode dial behaves.
        assertEquals(SceneIntent.AUTO, ModeStripLogic.modeAfterSteps(SceneIntent.AUTO, -1))
        assertEquals(SceneIntent.OBJECT, ModeStripLogic.modeAfterSteps(SceneIntent.OBJECT, 1))
    }
}
