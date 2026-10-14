package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

/**
 * Which screen edge an edge chevron anchors on, for every physical direction in every hold. This is the
 * one piece of the coaching layer that has to know about rotation, and it is exactly the shape of bug the
 * field report was about ("Move slightly right" pointing at the wrong edge in landscape), so the expected
 * values below are stated from gravity rather than copied from the implementation:
 *
 * At `ROTATION_90` the phone's **right edge is up**, so the physical right of the scene is at the bottom
 * of the screen and the physical top of the scene is at the screen's right — which is where the preview
 * visibly shows them in that hold, and what `RotationTruthTableTest` derives independently.
 */
class CueEdgeTest {

    @Test
    fun `physical right walks around the screen as the phone turns`() {
        assertEquals(CueEdge.RIGHT, CueEdge.forDirection(Direction.RIGHT, 0))
        assertEquals(CueEdge.BOTTOM, CueEdge.forDirection(Direction.RIGHT, 90))
        assertEquals(CueEdge.LEFT, CueEdge.forDirection(Direction.RIGHT, 180))
        assertEquals(CueEdge.TOP, CueEdge.forDirection(Direction.RIGHT, 270))
    }

    @Test
    fun `physical left is always the opposite edge from physical right`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val right = CueEdge.forDirection(Direction.RIGHT, rotation)
            val left = CueEdge.forDirection(Direction.LEFT, rotation)
            val opposite = when (right) {
                CueEdge.RIGHT -> CueEdge.LEFT
                CueEdge.LEFT -> CueEdge.RIGHT
                CueEdge.TOP -> CueEdge.BOTTOM
                CueEdge.BOTTOM -> CueEdge.TOP
                null -> error("RIGHT must always resolve to an edge")
            }
            assertEquals("at rotation $rotation", opposite, left)
        }
    }

    @Test
    fun `physical up walks around the screen too`() {
        assertEquals(CueEdge.TOP, CueEdge.forDirection(Direction.UP, 0))
        assertEquals(CueEdge.RIGHT, CueEdge.forDirection(Direction.UP, 90))
        assertEquals(CueEdge.BOTTOM, CueEdge.forDirection(Direction.UP, 180))
        assertEquals(CueEdge.LEFT, CueEdge.forDirection(Direction.UP, 270))
    }

    @Test
    fun `physical down is always the opposite edge from physical up`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val up = CueEdge.forDirection(Direction.UP, rotation)
            val down = CueEdge.forDirection(Direction.DOWN, rotation)
            val opposite = when (up) {
                CueEdge.TOP -> CueEdge.BOTTOM
                CueEdge.BOTTOM -> CueEdge.TOP
                CueEdge.LEFT -> CueEdge.RIGHT
                CueEdge.RIGHT -> CueEdge.LEFT
                null -> error("UP must always resolve to an edge")
            }
            assertEquals("at rotation $rotation", opposite, down)
        }
    }

    @Test
    fun `the edge is the one the rotated direction vector actually points at`() {
        // The load-bearing property: whatever the enum says, it must agree with the *same* rotation call
        // every piece of overlay geometry goes through. Recomputing it here from
        // OverlayMapper.rotateVectorToDisplay is what stops the two from ever drifting apart — a
        // hand-written 4x4 table in either place could silently disagree with the truth table.
        val physical = mapOf(
            Direction.RIGHT to (1f to 0f),
            Direction.LEFT to (-1f to 0f),
            Direction.UP to (0f to -1f),
            Direction.DOWN to (0f to 1f),
        )
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            for ((direction, vector) in physical) {
                val (dx, dy) = OverlayMapper.rotateVectorToDisplay(vector.first, vector.second, rotation)
                val expected = when {
                    abs(dx) > abs(dy) && dx > 0f -> CueEdge.RIGHT
                    abs(dx) > abs(dy) -> CueEdge.LEFT
                    dy > 0f -> CueEdge.BOTTOM
                    else -> CueEdge.TOP
                }
                assertEquals(
                    "$direction at rotation $rotation maps to screen vector ($dx, $dy)",
                    expected,
                    CueEdge.forDirection(direction, rotation),
                )
            }
        }
    }

    @Test
    fun `the physical-top edge agrees with the chrome stack's own edge`() {
        // The score chip, secondary icons and bubble level are laid out at the physical top; the chrome
        // stack already has its own answer for where that is, and the two must never disagree or a
        // `Raise` chevron would collide with the score chip in one hold and not another.
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val expected = when (chromeStackEdgeFor(rotation)) {
                ChromeStackEdge.TOP -> CueEdge.TOP
                ChromeStackEdge.RIGHT -> CueEdge.RIGHT
                ChromeStackEdge.BOTTOM -> CueEdge.BOTTOM
                ChromeStackEdge.LEFT -> CueEdge.LEFT
            }
            assertEquals("at rotation $rotation", expected, CueEdge.physicalTop(rotation))
        }
    }

    @Test
    fun `directions that are not edge cues have no edge`() {
        for (direction in listOf(
            Direction.CLOSER,
            Direction.BACK,
            Direction.ROTATE_CLOCKWISE,
            Direction.ROTATE_COUNTER_CLOCKWISE,
            Direction.NONE,
        )) {
            assertNull(CueEdge.forDirection(direction, 0))
        }
    }

    @Test
    fun `out-of-range and negative rotations normalize before mapping`() {
        assertEquals(CueEdge.BOTTOM, CueEdge.forDirection(Direction.RIGHT, 450)) // 450 % 360 == 90
        assertEquals(CueEdge.TOP, CueEdge.forDirection(Direction.RIGHT, -90)) // -90 + 360 == 270
    }
}
