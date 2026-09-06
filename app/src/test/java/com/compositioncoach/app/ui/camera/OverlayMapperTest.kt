package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayMapperTest {

    @Test
    fun `at rotation 0, top-left normalized point maps to view origin (plain multiply, unchanged)`() {
        val (x, y) = OverlayMapper.toPx(NormalizedPoint(0f, 0f), deviceRotationDegrees = 0, viewWidthPx = 1080f, viewHeightPx = 1920f)
        assertEquals(0f, x)
        assertEquals(0f, y)
    }

    @Test
    fun `at rotation 0, bottom-right normalized point maps to view width and height`() {
        val (x, y) = OverlayMapper.toPx(NormalizedPoint(1f, 1f), deviceRotationDegrees = 0, viewWidthPx = 1080f, viewHeightPx = 1920f)
        assertEquals(1080f, x)
        assertEquals(1920f, y)
    }

    @Test
    fun `at rotation 0, center normalized point maps to half the view size`() {
        val (x, y) = OverlayMapper.toPx(NormalizedPoint.CENTER, deviceRotationDegrees = 0, viewWidthPx = 1000f, viewHeightPx = 2000f)
        assertEquals(500f, x)
        assertEquals(1000f, y)
    }

    @Test
    fun `at rotation 0, rect maps each edge with a plain multiply`() {
        val rect = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.8f)
        val px = OverlayMapper.toPxRect(rect, deviceRotationDegrees = 0, viewWidthPx = 1000f, viewHeightPx = 1000f)
        assertEquals(100f, px.left)
        assertEquals(200f, px.top)
        assertEquals(600f, px.right)
        assertEquals(800f, px.bottom)
        assertEquals(500f, px.width)
        assertEquals(600f, px.height)
    }

    // --- Rotation mapping: physical-up normalized point -> the (never-rotating) portrait preview -----------
    // See OverlayMapper's class KDoc for the derivation; the ROTATION_90 case is the task's own worked
    // example: the physical top-left corner lands at the display's bottom-left.

    @Test
    fun `rotation 90 sends the physical top-left corner to the display bottom-left`() {
        val mapped = OverlayMapper.rotatePointToDisplay(NormalizedPoint(0f, 0f), deviceRotationDegrees = 90)
        assertEquals(0f, mapped.x, 1e-6f)
        assertEquals(1f, mapped.y, 1e-6f)
    }

    @Test
    fun `rotation 90 sends the physical top-right corner to the display top-left`() {
        val mapped = OverlayMapper.rotatePointToDisplay(NormalizedPoint(1f, 0f), deviceRotationDegrees = 90)
        assertEquals(0f, mapped.x, 1e-6f)
        assertEquals(0f, mapped.y, 1e-6f)
    }

    @Test
    fun `rotation 180 sends the physical top-left corner to the display bottom-right`() {
        val mapped = OverlayMapper.rotatePointToDisplay(NormalizedPoint(0f, 0f), deviceRotationDegrees = 180)
        assertEquals(1f, mapped.x, 1e-6f)
        assertEquals(1f, mapped.y, 1e-6f)
    }

    @Test
    fun `rotation 270 sends the physical top-left corner to the display top-right`() {
        val mapped = OverlayMapper.rotatePointToDisplay(NormalizedPoint(0f, 0f), deviceRotationDegrees = 270)
        assertEquals(1f, mapped.x, 1e-6f)
        assertEquals(0f, mapped.y, 1e-6f)
    }

    @Test
    fun `center is a fixed point under every rotation`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val mapped = OverlayMapper.rotatePointToDisplay(NormalizedPoint.CENTER, deviceRotationDegrees = rotation)
            assertEquals("rotation=$rotation", 0.5f, mapped.x, 1e-6f)
            assertEquals("rotation=$rotation", 0.5f, mapped.y, 1e-6f)
        }
    }

    @Test
    fun `four full turns of 90 degrees return to the identity`() {
        var point = NormalizedPoint(0.2f, 0.7f)
        for (i in 0 until 4) {
            point = OverlayMapper.rotatePointToDisplay(point, deviceRotationDegrees = 90)
        }
        assertEquals(0.2f, point.x, 1e-5f)
        assertEquals(0.7f, point.y, 1e-5f)
    }

    @Test
    fun `rect rotation stays axis-aligned and rotates corners consistently at 90`() {
        val rect = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.8f)
        val mapped = OverlayMapper.rotateRectToDisplay(rect, deviceRotationDegrees = 90)
        // Corners: (0.1,0.2)->(0.2,0.9), (0.6,0.8)->(0.8,0.4); sorted into an axis-aligned rect.
        assertEquals(0.2f, mapped.left, 1e-6f)
        assertEquals(0.4f, mapped.top, 1e-6f)
        assertEquals(0.8f, mapped.right, 1e-6f)
        assertEquals(0.9f, mapped.bottom, 1e-6f)
    }

    @Test
    fun `rect rotation at 180 flips both axes`() {
        val rect = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.8f)
        val mapped = OverlayMapper.rotateRectToDisplay(rect, deviceRotationDegrees = 180)
        assertEquals(0.4f, mapped.left, 1e-6f)
        assertEquals(0.2f, mapped.top, 1e-6f)
        assertEquals(0.9f, mapped.right, 1e-6f)
        assertEquals(0.8f, mapped.bottom, 1e-6f)
    }

    @Test
    fun `rect rotation at 270 mirrors the 90 case`() {
        val rect = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.8f)
        val mapped = OverlayMapper.rotateRectToDisplay(rect, deviceRotationDegrees = 270)
        // Corners: (0.1,0.2)->(0.8,0.1), (0.6,0.8)->(0.2,0.6); sorted into an axis-aligned rect.
        assertEquals(0.2f, mapped.left, 1e-6f)
        assertEquals(0.1f, mapped.top, 1e-6f)
        assertEquals(0.8f, mapped.right, 1e-6f)
        assertEquals(0.6f, mapped.bottom, 1e-6f)
    }

    @Test
    fun `toPx applies the rotation before the plain multiply`() {
        val (x, y) = OverlayMapper.toPx(NormalizedPoint(0f, 0f), deviceRotationDegrees = 90, viewWidthPx = 1000f, viewHeightPx = 2000f)
        assertEquals(0f, x, 1e-3f)
        assertEquals(2000f, y, 1e-3f)
    }

    // --- Direction vectors (arrow / rotate glyph / level indicator) -----------------------------------------

    @Test
    fun `a physical-right direction vector rotates consistently with the point mapping at 90 degrees`() {
        // Derived the same way rotatePointToDisplay is (see the class KDoc): map two physical points one
        // physical-right of each other and diff them, rather than asserting a screen direction from
        // intuition alone — this is what keeps the arrow/level-indicator math anchored to the one formula.
        val a = OverlayMapper.rotatePointToDisplay(NormalizedPoint(0.5f, 0.5f), deviceRotationDegrees = 90)
        val b = OverlayMapper.rotatePointToDisplay(NormalizedPoint(0.7f, 0.5f), deviceRotationDegrees = 90)
        val (dx, dy) = OverlayMapper.rotateVectorToDisplay(0.2f, 0f, deviceRotationDegrees = 90)
        assertEquals(b.x - a.x, dx, 1e-6f)
        assertEquals(b.y - a.y, dy, 1e-6f)
    }

    @Test
    fun `direction vectors have no translation component (origin maps to origin)`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val (dx, dy) = OverlayMapper.rotateVectorToDisplay(0f, 0f, deviceRotationDegrees = rotation)
            assertEquals("rotation=$rotation", 0f, dx, 1e-6f)
            assertEquals("rotation=$rotation", 0f, dy, 1e-6f)
        }
    }

    @Test
    fun `a direction vector's magnitude is preserved by rotation`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val (dx, dy) = OverlayMapper.rotateVectorToDisplay(1f, 0f, deviceRotationDegrees = rotation)
            assertEquals("rotation=$rotation", 1f, dx * dx + dy * dy, 1e-6f)
        }
    }
}
