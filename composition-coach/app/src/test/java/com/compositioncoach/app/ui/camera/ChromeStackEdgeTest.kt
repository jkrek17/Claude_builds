package com.compositioncoach.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ChromeStackEdgeTest {

    @Test
    fun `rotation 0 hugs the top (Pixel-standard placement)`() {
        assertEquals(ChromeStackEdge.TOP, chromeStackEdgeFor(0))
    }

    @Test
    fun `rotation 90 (right edge up) hugs the right`() {
        assertEquals(ChromeStackEdge.RIGHT, chromeStackEdgeFor(90))
    }

    @Test
    fun `rotation 180 (upside down) hugs the bottom`() {
        assertEquals(ChromeStackEdge.BOTTOM, chromeStackEdgeFor(180))
    }

    @Test
    fun `rotation 270 hugs the left`() {
        assertEquals(ChromeStackEdge.LEFT, chromeStackEdgeFor(270))
    }

    @Test
    fun `out-of-range and negative rotations normalize before mapping`() {
        assertEquals(ChromeStackEdge.RIGHT, chromeStackEdgeFor(450)) // 450 % 360 == 90
        assertEquals(ChromeStackEdge.LEFT, chromeStackEdgeFor(-90)) // -90 + 360 == 270
    }
}
