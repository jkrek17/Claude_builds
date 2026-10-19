package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.ui.theme.Accent
import com.compositioncoach.app.ui.theme.ScoreExcellent
import com.compositioncoach.app.ui.theme.ScoreGood
import com.compositioncoach.app.ui.theme.ScoreLow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The readiness arc's sweep and colour, which are what the photographer actually reads off the shutter.
 * Pure maths, so it is checkable without a Canvas — the drawing routine that consumes these is shared by
 * the shutter and the review card's score ring, so getting these two functions right gets both right.
 */
class ReadinessArcTest {

    @Test
    fun `the arc starts at 8 o'clock`() {
        // Compose measures from 3 o'clock, clockwise: hour * 30 - 90. Eight o'clock is 150 degrees.
        assertEquals(150f, ReadinessArc.START_ANGLE_DEGREES, 0f)
    }

    @Test
    fun `sweep is linear in the score, closing the full circle at 100`() {
        assertEquals(0f, ReadinessArc.sweepDegrees(0), 0.001f)
        assertEquals(90f, ReadinessArc.sweepDegrees(25), 0.001f)
        assertEquals(180f, ReadinessArc.sweepDegrees(50), 0.001f)
        assertEquals(360f, ReadinessArc.sweepDegrees(100), 0.001f)
    }

    @Test
    fun `an out-of-range score clamps instead of overdrawing the ring`() {
        assertEquals(0f, ReadinessArc.sweepDegrees(-20), 0.001f)
        assertEquals(360f, ReadinessArc.sweepDegrees(140), 0.001f)
    }

    @Test
    fun `colour follows the score tier - white below 70, amber to 87, green at 88`() {
        assertEquals(ScoreLow, ReadinessArc.color(0, isShootReady = false))
        assertEquals(ScoreLow, ReadinessArc.color(69, isShootReady = false))
        assertEquals(ScoreGood, ReadinessArc.color(70, isShootReady = false))
        assertEquals(ScoreGood, ReadinessArc.color(87, isShootReady = false))
        assertEquals(ScoreExcellent, ReadinessArc.color(88, isShootReady = false))
        assertEquals(ScoreExcellent, ReadinessArc.color(100, isShootReady = false))
    }

    @Test
    fun `shoot-ready paints the ring green whatever the number says`() {
        // The engine's own shoot-ready decision is the authority on this state, not the score tier: a
        // shot can be declared ready at a score the tier table would still colour amber.
        assertEquals(Accent.Ready, ReadinessArc.color(72, isShootReady = true))
        assertEquals(Accent.Ready, ReadinessArc.color(0, isShootReady = true))
    }
}
