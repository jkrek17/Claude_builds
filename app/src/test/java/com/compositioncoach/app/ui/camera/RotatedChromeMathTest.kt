package com.compositioncoach.app.ui.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotatedChromeMathTest {

    @Test
    fun `swapsAxes is true only for 90 and 270`() {
        assertFalse(RotatedChromeMath.swapsAxes(0))
        assertTrue(RotatedChromeMath.swapsAxes(90))
        assertFalse(RotatedChromeMath.swapsAxes(180))
        assertTrue(RotatedChromeMath.swapsAxes(270))
    }

    @Test
    fun `swapsAxes normalizes out-of-range and negative values`() {
        assertTrue(RotatedChromeMath.swapsAxes(450)) // 450 % 360 == 90
        assertTrue(RotatedChromeMath.swapsAxes(-90)) // -90 + 360 == 270
        assertFalse(RotatedChromeMath.swapsAxes(360))
    }

    @Test
    fun `boxSize is unchanged at 0 and 180`() {
        assertEquals(200 to 60, RotatedChromeMath.boxSize(contentWidth = 200, contentHeight = 60, deviceRotationDegrees = 0))
        assertEquals(200 to 60, RotatedChromeMath.boxSize(contentWidth = 200, contentHeight = 60, deviceRotationDegrees = 180))
    }

    @Test
    fun `boxSize swaps width and height at 90 and 270`() {
        assertEquals(60 to 200, RotatedChromeMath.boxSize(contentWidth = 200, contentHeight = 60, deviceRotationDegrees = 90))
        assertEquals(60 to 200, RotatedChromeMath.boxSize(contentWidth = 200, contentHeight = 60, deviceRotationDegrees = 270))
    }

    @Test
    fun `placementOffset is zero at 0 and 180 (box already matches content size)`() {
        assertEquals(0 to 0, RotatedChromeMath.placementOffset(200, 60, 0))
        assertEquals(0 to 0, RotatedChromeMath.placementOffset(200, 60, 180))
    }

    @Test
    fun `placementOffset centres a wide-short banner within its swapped (narrow-tall) box at 90`() {
        // A 200x60 banner rotated 90 degrees occupies a 60-wide, 200-tall box (see boxSize). Placing the
        // unrotated 200x60 content at ((60-200)/2, (200-60)/2) = (-70, 70) means its own centre sits at
        // (-70 + 100, 70 + 30) = (30, 100) -- exactly the swapped box's centre (60/2, 200/2) = (30, 100).
        val (offsetX, offsetY) = RotatedChromeMath.placementOffset(contentWidth = 200, contentHeight = 60, deviceRotationDegrees = 90)
        assertEquals(-70, offsetX)
        assertEquals(70, offsetY)
        val (boxWidth, boxHeight) = RotatedChromeMath.boxSize(200, 60, 90)
        assertEquals(boxWidth / 2f, offsetX + 200 / 2f, 1e-4f)
        assertEquals(boxHeight / 2f, offsetY + 60 / 2f, 1e-4f)
    }

    @Test
    fun `placementOffset keeps the content centred in its box for every rotation, for a range of sizes`() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            for ((w, h) in listOf(200 to 60, 60 to 200, 1 to 1, 300 to 40)) {
                val (offsetX, offsetY) = RotatedChromeMath.placementOffset(w, h, rotation)
                val (boxWidth, boxHeight) = RotatedChromeMath.boxSize(w, h, rotation)
                val label = "rotation=$rotation size=${w}x$h"
                assertEquals(label, boxWidth / 2f, offsetX + w / 2f, 1e-4f)
                assertEquals(label, boxHeight / 2f, offsetY + h / 2f, 1e-4f)
            }
        }
    }
}
