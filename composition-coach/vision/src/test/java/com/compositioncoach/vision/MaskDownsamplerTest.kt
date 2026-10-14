package com.compositioncoach.vision

import org.junit.Assert.assertTrue
import org.junit.Test

class MaskDownsamplerTest {

    /** Builds a source mask of [w]x[h], all zero except a "blob" (value 1f) in the block
     * [blobLeft,blobTop)..[blobLeft+blobSize,blobTop+blobSize). */
    private fun blobMask(w: Int, h: Int, blobLeft: Int, blobTop: Int, blobSize: Int): FloatArray {
        val arr = FloatArray(w * h)
        for (y in blobTop until (blobTop + blobSize).coerceAtMost(h)) {
            for (x in blobLeft until (blobLeft + blobSize).coerceAtMost(w)) {
                arr[y * w + x] = 1f
            }
        }
        return arr
    }

    @Test
    fun `identity mapping - no rotation no mirror - places a top-left blob at grid top-left`() {
        val mapper = FrameCoordinateMapper(
            sensorWidth = 480, sensorHeight = 640,
            rotationDegrees = 0,
            cropLeft = 0, cropTop = 0, cropRight = 480, cropBottom = 640,
            mirror = false,
        )
        // Source mask matches rotated-full size 1:1 here for simplicity; blob fills the top-left 10%.
        val source = blobMask(w = 480, h = 640, blobLeft = 0, blobTop = 0, blobSize = 48)

        val grid = MaskDownsampler.downsample(source, 480, 640, mapper, gridWidth = 32, gridHeight = 32)

        // Top-left cell should be fully inside the blob.
        assertTrue("expected top-left cell near 1f, was ${grid[0]}", grid[0] > 0.9f)
        // Bottom-right cell should be fully outside the blob.
        val bottomRight = grid[32 * 32 - 1]
        assertTrue("expected bottom-right cell near 0f, was $bottomRight", bottomRight < 0.1f)
    }

    @Test
    fun `rotation 90 with mirror - a blob at the sensor's top-left lands at the upright mirrored grid's top-left`() {
        // sensorWidth=640 (x), sensorHeight=480 (y) -> rotated-full is (480 wide) x (640 tall):
        // rotateForward at 90deg: (rx, ry) = (sensorHeight - y, x). Sensor top-left (0,0) -> (480, 0),
        // i.e. rotated-full's TOP-RIGHT corner. Mirroring x then flips that back to x=0 (top-left) of
        // the final upright/mirrored frame -- the two transforms cancel in x, but not in y, so this is
        // a genuine test of the composed rotation+mirror math, not a trivial identity.
        val mapper = FrameCoordinateMapper(
            sensorWidth = 640, sensorHeight = 480,
            rotationDegrees = 90,
            cropLeft = 0, cropTop = 0, cropRight = 640, cropBottom = 480,
            mirror = true,
        )
        // mapper.rotatedFullWidth = 480, rotatedFullHeight = 640 (sensorHeight/sensorWidth swapped).
        // Use a source mask at a coarser resolution (48x64, i.e. 1/10 scale) than rotated-full space
        // to also exercise the sourceWidth/rotatedFullWidth rescale, not just a 1:1 mapping.
        val sourceW = 48
        val sourceH = 64
        // Rotated-full top-right corner (where sensor top-left lands) is source x in [sourceW-5, sourceW), y in [0,5).
        val source = blobMask(w = sourceW, h = sourceH, blobLeft = sourceW - 5, blobTop = 0, blobSize = 5)

        val grid = MaskDownsampler.downsample(source, sourceW, sourceH, mapper, gridWidth = 32, gridHeight = 32)

        val topLeft = grid[0]
        val topRight = grid[31]
        val bottomLeft = grid[31 * 32]
        val bottomRight = grid[32 * 32 - 1]
        assertTrue("expected upright/mirrored top-left near 1f, was $topLeft", topLeft > 0.7f)
        assertTrue("expected top-right near 0f, was $topRight", topRight < 0.1f)
        assertTrue("expected bottom-left near 0f, was $bottomLeft", bottomLeft < 0.1f)
        assertTrue("expected bottom-right near 0f, was $bottomRight", bottomRight < 0.1f)
    }

    @Test
    fun `a uniform mask downsamples to a uniform grid at the same value`() {
        val mapper = FrameCoordinateMapper(
            sensorWidth = 320, sensorHeight = 240,
            rotationDegrees = 180,
            cropLeft = 0, cropTop = 0, cropRight = 320, cropBottom = 240,
            mirror = false,
        )
        val source = FloatArray(320 * 240) { 0.37f }

        val grid = MaskDownsampler.downsample(source, 320, 240, mapper, gridWidth = 32, gridHeight = 32)

        for (v in grid) assertTrue("expected ~0.37, was $v", kotlin.math.abs(v - 0.37f) < 1e-4f)
    }

    @Test
    fun `reused output buffer is filled in place without reallocating`() {
        val mapper = FrameCoordinateMapper(
            sensorWidth = 100, sensorHeight = 100,
            rotationDegrees = 0,
            cropLeft = 0, cropTop = 0, cropRight = 100, cropBottom = 100,
            mirror = false,
        )
        val source = FloatArray(100 * 100) { 0.5f }
        val reused = FloatArray(32 * 32)

        val result = MaskDownsampler.downsample(source, 100, 100, mapper, gridWidth = 32, gridHeight = 32, out = reused)

        assertTrue(result === reused)
    }
}
