package com.compositioncoach.vision

/**
 * Converts a YUV_420_888 frame (three planes copied into byte arrays, with their strides) into opaque ARGB
 * pixels in the frame's upright, crop-relative, mirrored space - the same space [FrameCoordinateMapper]
 * defines for every other output of the vision layer. Pure Kotlin, so it is unit-testable with synthetic
 * planes.
 *
 * The mapping from output pixel to sensor pixel is affine (rotation by a multiple of 90 degrees, a crop and an
 * optional mirror), so it is evaluated once for the origin and the two unit steps and then walked
 * incrementally: no per-pixel allocation, nearest-neighbour sampling (the output has the crop's own size, so
 * there is no resampling in the common case). Colour: full-range BT.601 (JFIF), which is what Android camera
 * HALs deliver for YUV_420_888.
 */
object YuvConverter {

    /** One plane: [data] holds the plane's bytes starting at its first pixel. */
    class Plane(val data: ByteArray, val rowStride: Int, val pixelStride: Int)

    /**
     * @param out reused when it is exactly `mapper.outputWidth * mapper.outputHeight`, else replaced.
     * @return the ARGB pixels, row-major, `mapper.outputWidth` x `mapper.outputHeight`.
     */
    fun toUprightArgb(y: Plane, u: Plane, v: Plane, mapper: FrameCoordinateMapper, out: IntArray? = null): IntArray {
        val w = mapper.outputWidth
        val h = mapper.outputHeight
        val result = out?.takeIf { it.size == w * h } ?: IntArray(w * h)
        val (x00, y00) = mapper.normalizedPointToSensor(0.5f / w, 0.5f / h)
        val (x10, y10) = mapper.normalizedPointToSensor(1.5f / w, 0.5f / h)
        val (x01, y01) = mapper.normalizedPointToSensor(0.5f / w, 1.5f / h)
        val stepXx = x10 - x00
        val stepXy = y10 - y00
        val stepYx = x01 - x00
        val stepYy = y01 - y00
        val maxX = mapper.sensorWidth - 1
        val maxY = mapper.sensorHeight - 1
        for (oy in 0 until h) {
            var sx = x00 + oy * stepYx
            var sy = y00 + oy * stepYy
            val row = oy * w
            for (ox in 0 until w) {
                val px = sx.toInt().coerceIn(0, maxX)
                val py = sy.toInt().coerceIn(0, maxY)
                result[row + ox] = argbAt(y, u, v, px, py)
                sx += stepXx
                sy += stepXy
            }
        }
        return result
    }

    private fun argbAt(y: Plane, u: Plane, v: Plane, px: Int, py: Int): Int {
        val luma = y.data.byteAt(py * y.rowStride + px * y.pixelStride)
        val cx = px shr 1
        val cy = py shr 1
        val cb = u.data.byteAt(cy * u.rowStride + cx * u.pixelStride) - 128
        val cr = v.data.byteAt(cy * v.rowStride + cx * v.pixelStride) - 128
        // Fixed point (x 1024): 1.402, 0.344136, 0.714136, 1.772.
        val r = luma + ((1436 * cr) shr 10)
        val g = luma - ((352 * cb + 731 * cr) shr 10)
        val b = luma + ((1815 * cb) shr 10)
        return (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
    }

    private fun ByteArray.byteAt(index: Int): Int = if (index in indices) this[index].toInt() and 0xFF else 128

    private fun clamp(c: Int): Int = if (c < 0) 0 else if (c > 255) 255 else c
}
