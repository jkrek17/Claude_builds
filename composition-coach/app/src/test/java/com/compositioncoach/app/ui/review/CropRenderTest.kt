package com.compositioncoach.app.ui.review

import com.compositioncoach.composition.model.NormalizedRect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** The clipping arithmetic behind crop cards, the What-if view and the dashed preview outline. */
class CropRenderTest {

    private val eps = 0.001f

    @Test
    fun `the crop window fills the target exactly`() {
        val crop = NormalizedRect(0.25f, 0.1f, 0.75f, 0.6f)
        val p = CropRender.placement(crop, targetW = 100f, targetH = 200f)
        assertEquals(200f, p.imageW, eps)
        assertEquals(400f, p.imageH, eps)
        assertEquals(-50f, p.offsetX, eps)
        assertEquals(-40f, p.offsetY, eps)
        // The window's right/bottom edges land on the target's right/bottom edges.
        assertEquals(100f, p.offsetX + crop.right * p.imageW, eps)
        assertEquals(200f, p.offsetY + crop.bottom * p.imageH, eps)
    }

    @Test
    fun `the full frame draws the image at the target size with no offset`() {
        val p = CropRender.placement(NormalizedRect.FULL, 120f, 160f)
        assertEquals(CropRender.Placement(120f, 160f, -0f, -0f), p)
    }

    @Test
    fun `a crop's pixel shape accounts for the photo's own aspect`() {
        // Half the width and half the height of a 3:4 photo is still 3:4.
        assertEquals(0.75f, CropRender.aspect(NormalizedRect(0f, 0f, 0.5f, 0.5f), 0.75f), eps)
        // A 1:1 crop of a 3:4 photo is 0.75 wide by 0.5625 tall in normalized units.
        assertEquals(1f, CropRender.aspect(NormalizedRect(0f, 0f, 0.75f, 0.5625f), 0.75f), eps)
        assertEquals(72f, CropRender.cardWidth(NormalizedRect(0f, 0f, 0.75f, 0.5625f), 0.75f, 72f), eps)
    }

    @Test
    fun `fit letterboxes a tall photo in a wide box and pillarboxes the other way`() {
        assertArrayEquals(floatArrayOf(25f, 0f, 75f, 100f), CropRender.fit(0.75f, 125f, 100f), eps)
        assertArrayEquals(floatArrayOf(0f, 20f, 100f, 75f), CropRender.fit(4f / 3f, 100f, 115f), eps)
    }

    @Test
    fun `the outline maps the window into the fitted photo`() {
        val (corner, size) = CropRender.outline(NormalizedRect(0.1f, 0.2f, 0.6f, 0.7f), 10f, 20f, 100f, 200f)
        assertEquals(20f, corner.x, eps)
        assertEquals(60f, corner.y, eps)
        assertEquals(50f, size.width, eps)
        assertEquals(100f, size.height, eps)
    }
}
