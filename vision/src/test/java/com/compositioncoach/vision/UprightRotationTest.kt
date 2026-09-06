package com.compositioncoach.vision

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rotation-correction table from [UprightRotation]'s KDoc: rear/front camera x all four quantized
 * device rotations. R0 = 90 for the rear camera and R0 = 270 for the front camera (a common Pixel-style
 * mount, front sensor 180 degrees opposite the rear one) are used as the two representative fixed
 * `imageInfo.rotationDegrees` values.
 *
 * The correction's **sign depends on camera facing**: `R0 - theta` for the rear camera, `R0 + theta` for
 * the front. The front camera's optical axis points the opposite way (image-right is device-*left*), so
 * physically rotating the phone sweeps the scene the other way inside its buffer — see the class KDoc's
 * derivation, and `:app`'s `RotationTruthTableTest`, which drives a marked pixel through a modelled
 * sensor buffer for both facings. An earlier version used the rear formula for both, which left the front
 * camera's analysis frame upside down in both landscape holds (the two differ by 2 * theta = 180 there).
 *
 * Mirroring is handled separately by [FrameCoordinateMapper] and is unaffected by which rotation produced
 * the upright frame.
 */
class UprightRotationTest {

    private val rearR0 = 90
    private val frontR0 = 270

    @Test
    fun `rear camera, natural portrait (theta=0) is already upright`() {
        assertEquals(90, UprightRotation.computeUprightRotationDegrees(rearR0, 0, isFrontCamera = false))
    }

    @Test
    fun `rear camera, rotated 90 CCW to landscape (theta=90, ROTATION_90) needs no further rotation`() {
        // The task's own worked example: top of phone pointing left.
        assertEquals(0, UprightRotation.computeUprightRotationDegrees(rearR0, 90, isFrontCamera = false))
    }

    @Test
    fun `rear camera, upside down (theta=180)`() {
        assertEquals(270, UprightRotation.computeUprightRotationDegrees(rearR0, 180, isFrontCamera = false))
    }

    @Test
    fun `rear camera, rotated 90 CW to reverse landscape (theta=270, ROTATION_270)`() {
        assertEquals(180, UprightRotation.computeUprightRotationDegrees(rearR0, 270, isFrontCamera = false))
    }

    @Test
    fun `front camera, natural portrait (theta=0)`() {
        assertEquals(270, UprightRotation.computeUprightRotationDegrees(frontR0, 0, isFrontCamera = true))
    }

    @Test
    fun `front camera, theta=90 turns the buffer further clockwise, not further counter-clockwise`() {
        // (270 + 90) % 360. The rear formula would give 180 here -- 180 degrees out, i.e. upside down.
        assertEquals(0, UprightRotation.computeUprightRotationDegrees(frontR0, 90, isFrontCamera = true))
    }

    @Test
    fun `front camera, theta=180 (the one hold where both facings agree)`() {
        assertEquals(90, UprightRotation.computeUprightRotationDegrees(frontR0, 180, isFrontCamera = true))
    }

    @Test
    fun `front camera, theta=270`() {
        assertEquals(180, UprightRotation.computeUprightRotationDegrees(frontR0, 270, isFrontCamera = true))
    }

    @Test
    fun `the two facings agree only at theta 0 and 180, and differ by 180 degrees at 90 and 270`() {
        for (theta in intArrayOf(0, 90, 180, 270)) {
            val rear = UprightRotation.computeUprightRotationDegrees(90, theta, isFrontCamera = false)
            val front = UprightRotation.computeUprightRotationDegrees(90, theta, isFrontCamera = true)
            val expectedDifference = if (theta == 0 || theta == 180) 0 else 180
            assertEquals("theta=$theta", expectedDifference, ((front - rear) % 360 + 360) % 360)
        }
    }

    @Test
    fun `result is always one of the four supported rotations`() {
        for (r0 in intArrayOf(0, 90, 180, 270)) {
            for (theta in intArrayOf(0, 90, 180, 270)) {
                val result = UprightRotation.computeUprightRotationDegrees(r0, theta, isFrontCamera = theta % 180 == 0)
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
            val rotation = UprightRotation.computeUprightRotationDegrees(frontR0, theta, isFrontCamera = true)
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
