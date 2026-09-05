package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.NormalizedPoint
import com.compositioncoach.composition.model.NormalizedRect
import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayMapperTest {

    @Test
    fun `top-left normalized point maps to view origin`() {
        val (x, y) = OverlayMapper.toPx(NormalizedPoint(0f, 0f), viewWidthPx = 1080f, viewHeightPx = 1920f)
        assertEquals(0f, x)
        assertEquals(0f, y)
    }

    @Test
    fun `bottom-right normalized point maps to view width and height`() {
        val (x, y) = OverlayMapper.toPx(NormalizedPoint(1f, 1f), viewWidthPx = 1080f, viewHeightPx = 1920f)
        assertEquals(1080f, x)
        assertEquals(1920f, y)
    }

    @Test
    fun `center normalized point maps to half the view size`() {
        val (x, y) = OverlayMapper.toPx(NormalizedPoint.CENTER, viewWidthPx = 1000f, viewHeightPx = 2000f)
        assertEquals(500f, x)
        assertEquals(1000f, y)
    }

    @Test
    fun `rect maps each edge with a plain multiply`() {
        val rect = NormalizedRect(left = 0.1f, top = 0.2f, right = 0.6f, bottom = 0.8f)
        val px = OverlayMapper.toPxRect(rect, viewWidthPx = 1000f, viewHeightPx = 1000f)
        assertEquals(100f, px.left)
        assertEquals(200f, px.top)
        assertEquals(600f, px.right)
        assertEquals(800f, px.bottom)
        assertEquals(500f, px.width)
        assertEquals(600f, px.height)
    }
}
