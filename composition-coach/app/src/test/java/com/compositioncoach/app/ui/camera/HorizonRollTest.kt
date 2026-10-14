package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.OverlayGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bubble level's roll angle, including the two corrections that are easy to forget. */
class HorizonRollTest {

    private fun line(x0: Float, y0: Float, x1: Float, y1: Float) =
        OverlayGeometry.Line(NormalizedPoint(x0, y0), NormalizedPoint(x1, y1))

    @Test
    fun `a flat horizon reads as level in every hold`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val degrees = HorizonRoll.degreesFor(line(0.1f, 0.5f, 0.9f, 0.5f), rotation)
            assertEquals("at rotation $rotation", 0f, degrees, 0.001f)
            assertTrue(HorizonRoll.isLevel(degrees))
        }
    }

    @Test
    fun `no horizon signal reads as level rather than as a wild angle`() {
        assertEquals(0f, HorizonRoll.degreesFor(null, 0), 0f)
    }

    @Test
    fun `a tilted horizon is reported in the direction it actually leans`() {
        // End lower than start: the line falls to the right, a clockwise (positive) tilt on screen.
        assertTrue(HorizonRoll.degreesFor(line(0.2f, 0.4f, 0.8f, 0.6f), 0) > 0f)
        assertTrue(HorizonRoll.degreesFor(line(0.2f, 0.6f, 0.8f, 0.4f), 0) < 0f)
    }

    @Test
    fun `the answer does not depend on which end of the line came first`() {
        val forward = HorizonRoll.degreesFor(line(0.2f, 0.4f, 0.8f, 0.6f), 0)
        val reversed = HorizonRoll.degreesFor(line(0.8f, 0.6f, 0.2f, 0.4f), 0)
        assertEquals(forward, reversed, 0.001f)
    }

    @Test
    fun `the normalized frame's aspect ratio is corrected for`() {
        // The same normalized slope is a different on-screen angle in a 3:4 box than in a square one: a
        // rise of 0.1 over a run of 0.5 is atan(0.1 * 4 / (0.5 * 3)) = 14.9 degrees, not atan(0.2) = 11.3.
        val degrees = HorizonRoll.degreesFor(line(0.25f, 0.45f, 0.75f, 0.55f), 0)
        assertEquals(14.93f, degrees, 0.05f)
    }

    @Test
    fun `the aspect correction flips with the axes in a landscape hold`() {
        // At 90 or 270 the physical frame lies across the portrait preview sideways, so the two axes'
        // extents swap and the same normalized line reads as a shallower angle.
        val portrait = HorizonRoll.degreesFor(line(0.25f, 0.45f, 0.75f, 0.55f), 0)
        val landscape = HorizonRoll.degreesFor(line(0.25f, 0.45f, 0.75f, 0.55f), 90)
        assertEquals(8.53f, landscape, 0.05f)
        assertTrue(landscape < portrait)
    }

    @Test
    fun `level tolerance is a degree and a half either way`() {
        assertTrue(HorizonRoll.isLevel(1.4f))
        assertTrue(HorizonRoll.isLevel(-1.4f))
        assertFalse(HorizonRoll.isLevel(2f))
        assertFalse(HorizonRoll.isLevel(-2f))
    }
}
