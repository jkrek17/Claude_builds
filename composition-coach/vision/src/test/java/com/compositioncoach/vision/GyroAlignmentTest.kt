package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class GyroAlignmentTest {

    @Test
    fun `rear camera pan right moves the scene left by f times theta`() {
        // Turning toward the right is a negative rotation about +y; f = 0.75 * 1280 = 960 px; 0.01 rad -> 9.6 px.
        val shift = GyroAlignment.contentShift(AngularDelta(0f, -0.01f, 0f), longEdgePx = 1280, focalToLongEdge = 0.75f, isFrontCamera = false)
        assertEquals(PixelShift(-10, 0), shift)
    }

    @Test
    fun `rear camera tilt up moves the scene down`() {
        val shift = GyroAlignment.contentShift(AngularDelta(0.005f, 0f, 0f), 1000, 1f, isFrontCamera = false)
        assertEquals(PixelShift(0, 5), shift)
    }

    @Test
    fun `front camera mirrored preview reverses both axes`() {
        val shift = GyroAlignment.contentShift(AngularDelta(0.005f, 0.01f, 0.3f), 1000, 1f, isFrontCamera = true)
        assertEquals(PixelShift(-10, -5), shift)
    }

    @Test
    fun `display shifts map into the stored buffer for each sensor rotation`() {
        val s = PixelShift(3, 1)
        assertEquals(s, GyroAlignment.toBuffer(s, 0))
        // At 90 degrees buffer (x, y) shows as (h - y, x): a display move right is a buffer move toward -y.
        assertEquals(PixelShift(1, -3), GyroAlignment.toBuffer(s, 90))
        assertEquals(PixelShift(-3, -1), GyroAlignment.toBuffer(s, 180))
        assertEquals(PixelShift(-1, 3), GyroAlignment.toBuffer(s, 270))
    }

    @Test
    fun `buffer shift round-trips through the frame mapper`() {
        // A buffer vector, rotated forward by the mapper, must come back as the display shift we started from.
        val mapper = FrameCoordinateMapper(400, 300, 90, 0, 0, 400, 300, mirror = false)
        val display = PixelShift(7, -4)
        val buffer = GyroAlignment.toBuffer(display, 90)
        val a = mapper.sensorPointToNormalized(100f, 100f)
        val b = mapper.sensorPointToNormalized(100f + buffer.dx, 100f + buffer.dy)
        assertEquals(display.dx.toFloat(), (b.x - a.x) * mapper.outputWidth, 1e-3f)
        assertEquals(display.dy.toFloat(), (b.y - a.y) * mapper.outputHeight, 1e-3f)
    }

    @Test
    fun `clamp limits both components`() {
        assertEquals(PixelShift(5, -5), GyroAlignment.clamp(PixelShift(9, -12), 5))
    }
}
