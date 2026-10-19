package com.compositioncoach.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class AutoLevelGeometryTest {

    @Test
    fun `no rotation keeps the whole image`() {
        assertEquals(1.0, AutoLevelGeometry.cropScale(0.0, 4000, 3000), 1e-12)
        assertEquals(4000 to 3000, AutoLevelGeometry.cropSize(4000, 3000, 0.0))
    }

    @Test
    fun `five degrees on a 4 by 3 frame keeps about 90 percent per side`() {
        // 1 / (cos 5 + 4/3 sin 5) = 1 / (0.99619 + 0.11621) = 0.89896
        assertEquals(0.89896, AutoLevelGeometry.cropScale(5.0, 4000, 3000), 1e-4)
        val (w, h) = AutoLevelGeometry.cropSize(4000, 3000, 5.0)
        assertEquals(3595, w)
        assertEquals(2696, h)
    }

    @Test
    fun `the scale is the same in portrait and landscape and for either sign`() {
        val a = AutoLevelGeometry.cropScale(3.0, 4080, 3072)
        assertEquals(a, AutoLevelGeometry.cropScale(3.0, 3072, 4080), 1e-12)
        assertEquals(a, AutoLevelGeometry.cropScale(-3.0, 4080, 3072), 1e-12)
    }

    @Test
    fun `the crop rectangle always lies inside the rotated image`() {
        val sizes = listOf(4000 to 3000, 3000 to 4000, 4080 to 3072, 1920 to 1080)
        for ((width, height) in sizes) {
            for (tenths in -80..80) {
                val angle = tenths / 10.0
                val (w, h) = AutoLevelGeometry.cropSize(width, height, angle)
                // Rotate the crop's corners into the image's own axes; each must stay within the image.
                val t = Math.toRadians(angle)
                for ((sx, sy) in listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)) {
                    val x = sx * w / 2.0
                    val y = sy * h / 2.0
                    val xr = x * cos(t) - y * sin(t)
                    val yr = x * sin(t) + y * cos(t)
                    assertTrue("angle=$angle size=${width}x$height", abs(xr) <= width / 2.0 + 1e-6)
                    assertTrue("angle=$angle size=${width}x$height", abs(yr) <= height / 2.0 + 1e-6)
                }
                // ...and keeps the original aspect ratio to within a pixel.
                assertEquals(width.toDouble() / height, w.toDouble() / h, 2.0 / h)
            }
        }
    }

    @Test
    fun `the crop is the largest that fits, within a pixel`() {
        val (w, _) = AutoLevelGeometry.cropSize(4000, 3000, 4.0)
        val bigger = w + 2
        val t = Math.toRadians(4.0)
        val biggerH = bigger * 3000.0 / 4000
        val fits = bigger * sin(t) + biggerH * cos(t) <= 3000 && bigger * cos(t) + biggerH * sin(t) <= 4000
        assertFalse(fits)
    }

    @Test
    fun `only tilts between 0_7 and 8 degrees with a reliable reading are levelled`() {
        assertFalse(AutoLevelGeometry.shouldLevel(0.5f, true))
        assertTrue(AutoLevelGeometry.shouldLevel(0.7f, true))
        assertTrue(AutoLevelGeometry.shouldLevel(-3f, true))
        assertTrue(AutoLevelGeometry.shouldLevel(8f, true))
        assertFalse(AutoLevelGeometry.shouldLevel(8.5f, true))
        assertFalse(AutoLevelGeometry.shouldLevel(3f, false))
        assertFalse(AutoLevelGeometry.shouldLevel(Float.NaN, true))
    }

    @Test
    fun `buffer rotation is the negated roll, flipped again for mirrored orientations`() {
        // Horizon appears 2 deg clockwise: turn the displayed image 2 deg counter-clockwise.
        assertEquals(-2f, AutoLevelGeometry.bufferRotationDegrees(2f, ExifOrientations.NORMAL), 0f)
        assertEquals(-2f, AutoLevelGeometry.bufferRotationDegrees(2f, ExifOrientations.ROTATE_90), 0f)
        assertEquals(-2f, AutoLevelGeometry.bufferRotationDegrees(2f, ExifOrientations.ROTATE_270), 0f)
        // A mirror between buffer and display reverses the sense of rotation.
        assertEquals(2f, AutoLevelGeometry.bufferRotationDegrees(2f, ExifOrientations.FLIP_HORIZONTAL), 0f)
        assertEquals(2f, AutoLevelGeometry.bufferRotationDegrees(2f, ExifOrientations.TRANSPOSE), 0f)
        assertEquals(2f, AutoLevelGeometry.bufferRotationDegrees(2f, ExifOrientations.TRANSVERSE), 0f)
    }
}
