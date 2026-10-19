package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCoordinateMapperTest {

    private val eps = 1e-4f

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>) {
        assertEquals(expectedX, actual.first, eps)
        assertEquals(expectedY, actual.second, eps)
    }

    // --- Rotation 90, no crop, no mirror (typical rear camera in portrait) ---------------------

    @Test
    fun `rotation 90 no crop no mirror maps sensor corners to upright corners`() {
        val mapper = FrameCoordinateMapper(
            sensorWidth = 640, sensorHeight = 480,
            rotationDegrees = 90,
            cropLeft = 0, cropTop = 0, cropRight = 640, cropBottom = 480,
            mirror = false,
        )
        // Sensor top-left -> upright top-right; top-right -> bottom-right;
        // bottom-left -> top-left; bottom-right -> bottom-left. (Rotating a photo 90 deg CW.)
        assertPoint(1f, 0f, mapper.sensorPointToNormalized(0f, 0f).let { it.x to it.y })
        assertPoint(1f, 1f, mapper.sensorPointToNormalized(640f, 0f).let { it.x to it.y })
        assertPoint(0f, 0f, mapper.sensorPointToNormalized(0f, 480f).let { it.x to it.y })
        assertPoint(0f, 1f, mapper.sensorPointToNormalized(640f, 480f).let { it.x to it.y })
        assertEquals(480, mapper.outputWidth)
        assertEquals(640, mapper.outputHeight)
    }

    @Test
    fun `rotation 90 with a non-square crop rect offsets and rescales correctly`() {
        val mapper = FrameCoordinateMapper(
            sensorWidth = 640, sensorHeight = 480,
            rotationDegrees = 90,
            cropLeft = 100, cropTop = 50, cropRight = 500, cropBottom = 350,
            mirror = false,
        )
        // Crop is 400 (sensor-x) by 300 (sensor-y); after a 90 deg rotation sensor-x becomes the
        // upright height and sensor-y becomes the upright width.
        assertEquals(300, mapper.outputWidth)
        assertEquals(400, mapper.outputHeight)

        // Center of the crop must land on the center of the normalized frame regardless of rotation.
        val center = mapper.sensorPointToNormalized(300f, 200f)
        assertEquals(0.5f, center.x, eps)
        assertEquals(0.5f, center.y, eps)
    }

    // --- Rotation 270 + mirror (typical front camera in portrait) ------------------------------

    @Test
    fun `rotation 270 with mirror maps sensor corners to upright mirrored corners`() {
        val mapper = FrameCoordinateMapper(
            sensorWidth = 640, sensorHeight = 480,
            rotationDegrees = 270,
            cropLeft = 0, cropTop = 0, cropRight = 640, cropBottom = 480,
            mirror = true,
        )
        assertPoint(1f, 1f, mapper.sensorPointToNormalized(0f, 0f).let { it.x to it.y })
        assertPoint(1f, 0f, mapper.sensorPointToNormalized(640f, 0f).let { it.x to it.y })
        assertPoint(0f, 1f, mapper.sensorPointToNormalized(0f, 480f).let { it.x to it.y })
        assertPoint(0f, 0f, mapper.sensorPointToNormalized(640f, 480f).let { it.x to it.y })
    }

    @Test
    fun `mirroring flips a rect's screen-left-right without breaking min-max ordering`() {
        // Rotation 0 isolates the mirroring behaviour: sensor-x maps directly to upright-x, so a box
        // in the sensor's left third stays in the upright-left third pre-mirror, and moves to the
        // upright-right third once mirrored.
        val mirrored = FrameCoordinateMapper(
            sensorWidth = 640, sensorHeight = 480,
            rotationDegrees = 0,
            cropLeft = 0, cropTop = 0, cropRight = 640, cropBottom = 480,
            mirror = true,
        )
        val notMirrored = FrameCoordinateMapper(
            sensorWidth = 640, sensorHeight = 480,
            rotationDegrees = 0,
            cropLeft = 0, cropTop = 0, cropRight = 640, cropBottom = 480,
            mirror = false,
        )
        // A box occupying the sensor's left third (x in [0,213]).
        val rectMirrored = mirrored.sensorRectToNormalized(0f, 0f, 213f, 480f)
        val rectPlain = notMirrored.sensorRectToNormalized(0f, 0f, 213f, 480f)
        // Still a valid rect (left < right) in both cases...
        assert(rectMirrored.left < rectMirrored.right)
        assert(rectPlain.left < rectPlain.right)
        // ...but mirroring should have moved it to the opposite side of the frame.
        assert(rectPlain.right < 0.5f) // occupies the upright-left side pre-mirror
        assert(rectMirrored.left > 0.5f) // same physical box occupies the screen-right side once mirrored
    }

    // --- Sanity checks for 0 and 180, and the inverse mapping -----------------------------------

    @Test
    fun `rotation 0 is identity aside from normalization`() {
        val mapper = FrameCoordinateMapper(
            sensorWidth = 200, sensorHeight = 100,
            rotationDegrees = 0,
            cropLeft = 0, cropTop = 0, cropRight = 200, cropBottom = 100,
            mirror = false,
        )
        val p = mapper.sensorPointToNormalized(50f, 25f)
        assertEquals(0.25f, p.x, eps)
        assertEquals(0.25f, p.y, eps)
    }

    @Test
    fun `rotation 180 flips both axes`() {
        val mapper = FrameCoordinateMapper(
            sensorWidth = 200, sensorHeight = 100,
            rotationDegrees = 180,
            cropLeft = 0, cropTop = 0, cropRight = 200, cropBottom = 100,
            mirror = false,
        )
        assertPoint(1f, 1f, mapper.sensorPointToNormalized(0f, 0f).let { it.x to it.y })
        assertPoint(0f, 0f, mapper.sensorPointToNormalized(200f, 100f).let { it.x to it.y })
    }

    @Test
    fun `normalizedPointToSensor is the inverse of sensorPointToNormalized`() {
        val mapper = FrameCoordinateMapper(
            sensorWidth = 640, sensorHeight = 480,
            rotationDegrees = 90,
            cropLeft = 40, cropTop = 20, cropRight = 600, cropBottom = 460,
            mirror = true,
        )
        val originalX = 321f
        val originalY = 111f
        val normalized = mapper.sensorPointToNormalized(originalX, originalY)
        val (backX, backY) = mapper.normalizedPointToSensor(normalized.x, normalized.y)
        assertEquals(originalX, backX, 1e-2f)
        assertEquals(originalY, backY, 1e-2f)
    }
}
