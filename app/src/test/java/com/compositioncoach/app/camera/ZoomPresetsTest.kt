package com.compositioncoach.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZoomPresetsTest {

    @Test
    fun `main camera only offers 1x and 2x within its digital range`() {
        assertEquals(listOf(1f, 2f), ZoomPresets.compute(1f, 4f))
    }

    @Test
    fun `an ultra-wide below 0_6 stops at 0_6`() {
        assertEquals(listOf(0.6f, 1f, 2f), ZoomPresets.compute(0.5f, 4f))
    }

    @Test
    fun `an ultra-wide above 0_6 is rounded up to one decimal`() {
        assertEquals(listOf(0.7f, 1f, 2f), ZoomPresets.compute(0.67f, 3f))
        assertEquals(listOf(0.6f, 1f, 2f), ZoomPresets.compute(0.6f, 3f))
    }

    @Test
    fun `5x is offered only when the camera reaches it`() {
        assertEquals(listOf(0.6f, 1f, 2f, 5f), ZoomPresets.compute(0.55f, 30f))
        assertEquals(listOf(1f, 2f, 5f), ZoomPresets.compute(1f, 5f))
        assertEquals(listOf(1f, 2f), ZoomPresets.compute(1f, 4.9f))
    }

    @Test
    fun `a fixed-zoom camera gets a single 1x chip`() {
        assertEquals(listOf(1f), ZoomPresets.compute(1f, 1f))
    }

    @Test
    fun `a minimum just under 1 does not produce a 1x duplicate`() {
        assertEquals(listOf(1f, 2f), ZoomPresets.compute(0.95f, 2f))
    }

    @Test
    fun `nonsense ranges fall back to 1x`() {
        assertEquals(listOf(1f), ZoomPresets.compute(0f, 4f))
        assertEquals(listOf(1f), ZoomPresets.compute(2f, 1f))
    }

    @Test
    fun `selected preset tolerates small offsets`() {
        val presets = listOf(0.6f, 1f, 2f)
        assertEquals(1f, ZoomPresets.selectedPreset(presets, 1.02f))
        assertEquals(2f, ZoomPresets.selectedPreset(presets, 1.95f))
        assertNull(ZoomPresets.selectedPreset(presets, 1.5f))
    }
}
