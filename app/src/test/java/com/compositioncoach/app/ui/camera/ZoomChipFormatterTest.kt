package com.compositioncoach.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomChipFormatterTest {

    @Test
    fun `format shows one decimal place with the multiplication sign`() {
        assertEquals("1.0×", ZoomChipFormatter.format(1f))
        assertEquals("2.5×", ZoomChipFormatter.format(2.5f))
        assertEquals("10.0×", ZoomChipFormatter.format(10f))
    }

    @Test
    fun `format rounds rather than truncates`() {
        assertEquals("1.3×", ZoomChipFormatter.format(1.25f))
        assertEquals("1.2×", ZoomChipFormatter.format(1.24f))
    }
}
