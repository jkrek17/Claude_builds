package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rotation-correction table from [UprightRotation]'s KDoc: rear/front camera x all four quantized
 * device rotations. R0 = 90 for the rear camera and R0 = 270 for the front camera (a common Pixel-style
 * mount, front sensor 180 degrees opposite the rear one) are used as the two representative fixed
 * `imageInfo.rotationDegrees` values; the formula itself doesn't otherwise depend on camera facing (see
 * the class KDoc's "front camera unmodified" note) — mirroring is handled separately by
 * [FrameCoordinateMapper] and is unaffected by which rotation produced the upright frame.
 */
class UprightRotationTest {

    private val rearR0 = 90
    private val frontR0 = 270

    @Test
    fun `rear camera, natural portrait (theta=0) is already upright`() {
        assertEquals(90, UprightRotation.computeUprightRotationDegrees(rearR0, 0))
    }

    @Test
    fun `rear camera, rotated 90 CCW to landscape (theta=90, ROTATION_90) needs no further rotation`() {
        // The task's own worked example: top of phone pointing left.
        assertEquals(0, UprightRotation.computeUprightRotationDegrees(rearR0, 90))
    }

    @Test
    fun `rear camera, upside down (theta=180)`() {
        assertEquals(270, UprightRotation.computeUprightRotationDegrees(rearR0, 180))
    }

    @Test
    fun `rear camera, rotated 90 CW to reverse landscape (theta=270, ROTATION_270)`() {
        assertEquals(180, UprightRotation.computeUprightRotationDegrees(rearR0, 270))
    }

    @Test
    fun `front camera, natural portrait (theta=0)`() {
        assertEquals(270, UprightRotation.computeUprightRotationDegrees(frontR0, 0))
    }

    @Test
    fun `front camera, theta=90`() {
        assertEquals(180, UprightRotation.computeUprightRotationDegrees(frontR0, 90))
    }

    @Test
    fun `front camera, theta=180`() {
        assertEquals(90, UprightRotation.computeUprightRotationDegrees(frontR0, 180))
    }

    @Test
    fun `front camera, theta=270`() {
        assertEquals(0, UprightRotation.computeUprightRotationDegrees(frontR0, 270))
    }

    @Test
    fun `result is always one of the four supported rotations`() {
        for (r0 in intArrayOf(0, 90, 180, 270)) {
            for (theta in intArrayOf(0, 90, 180, 270)) {
                val result = UprightRotation.computeUprightRotationDegrees(r0, theta)
                assertEquals(0, result % 90)
                org.junit.Assert.assertTrue("result=$result should be in 0..270", result in 0..270)
            }
        }
    }

    @Test
    fun `mirroring is independent of the corrected rotation (FrameCoordinateMapper mirrors x' = 1 - x post-rotation)`() {
        // Regardless of which upright rotation a frame used, the front camera's mirror should still
        // flip left/right in the *final* upright frame — sanity-checked here at all four rotations for
        // a simple sensor point, using a square 100x100 buffer with no crop.
        for (theta in intArrayOf(0, 90, 180, 270)) {
            val rotation = UprightRotation.computeUprightRotationDegrees(frontR0, theta)
            val mapper = FrameCoordinateMapper(
                sensorWidth = 100,
                sensorHeight = 100,
                rotationDegrees = rotation,
                cropLeft = 0,
                cropTop = 0,
                cropRight = 100,
                cropBottom = 100,
                mirror = true,
            )
            val mirroredMapper = FrameCoordinateMapper(
                sensorWidth = 100,
                sensorHeight = 100,
                rotationDegrees = rotation,
                cropLeft = 0,
                cropTop = 0,
                cropRight = 100,
                cropBottom = 100,
                mirror = false,
            )
            val withMirror = mapper.sensorPointToNormalized(0f, 0f)
            val withoutMirror = mirroredMapper.sensorPointToNormalized(0f, 0f)
            assertEquals(1f - withoutMirror.x, withMirror.x, 1e-4f)
            assertEquals(withoutMirror.y, withMirror.y, 1e-4f)
        }
    }
}
