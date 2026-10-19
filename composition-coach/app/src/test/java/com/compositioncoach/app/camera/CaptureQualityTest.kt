package com.compositioncoach.app.camera

import com.compositioncoach.app.camera.CaptureQuality.PixelSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQualityTest {

    @Test
    fun `largest 4 by 3 ignores wider and smaller sizes`() {
        val sizes = listOf(
            PixelSize(4080, 3072),
            PixelSize(4080, 2296), // 16:9, more pixels than some 4:3 sizes but wrong aspect
            PixelSize(4000, 3000),
            PixelSize(1920, 1440),
            PixelSize(8160, 4590), // 16:9 high-res
        )
        assertEquals(PixelSize(4080, 3072), CaptureQuality.largestFourByThree(sizes))
        assertNull(CaptureQuality.largestFourByThree(listOf(PixelSize(1920, 1080))))
    }

    @Test
    fun `portrait 3 by 4 counts as 4 by 3`() {
        assertTrue(CaptureQuality.isFourByThree(PixelSize(3024, 4032)))
        assertFalse(CaptureQuality.isFourByThree(PixelSize(0, 0)))
    }

    @Test
    fun `unavailable enhancements resolve to OFF and available ones are kept`() {
        val available = setOf(CaptureEnhancement.OFF, CaptureEnhancement.NIGHT)
        assertEquals(CaptureEnhancement.NIGHT, CaptureQuality.resolveEnhancement(CaptureEnhancement.NIGHT, available))
        assertEquals(CaptureEnhancement.OFF, CaptureQuality.resolveEnhancement(CaptureEnhancement.AUTO, available))
        assertEquals(CaptureEnhancement.OFF, CaptureQuality.resolveEnhancement(CaptureEnhancement.OFF, emptySet()))
    }

    @Test
    fun `AUTO is offered only when it keeps live coaching, HDR and NIGHT whenever available`() {
        val all = setOf(CaptureEnhancement.AUTO, CaptureEnhancement.HDR, CaptureEnhancement.NIGHT)
        assertEquals(
            setOf(CaptureEnhancement.OFF, CaptureEnhancement.HDR, CaptureEnhancement.NIGHT),
            CaptureQuality.offeredEnhancements(all, analysisSupported = emptySet(), needsAnalysis = true),
        )
        assertEquals(
            setOf(CaptureEnhancement.OFF) + all,
            CaptureQuality.offeredEnhancements(all, analysisSupported = setOf(CaptureEnhancement.AUTO), needsAnalysis = true),
        )
        assertEquals(
            setOf(CaptureEnhancement.OFF) + all,
            CaptureQuality.offeredEnhancements(all, analysisSupported = emptySet(), needsAnalysis = false),
        )
        assertEquals(setOf(CaptureEnhancement.OFF), CaptureQuality.offeredEnhancements(emptySet(), emptySet(), true))
        // The persisted AUTO default then resolves to OFF (lucky imaging) rather than pausing coaching.
        val offered = CaptureQuality.offeredEnhancements(all, emptySet(), true)
        assertEquals(CaptureEnhancement.OFF, CaptureQuality.resolveEnhancement(CaptureEnhancement.AUTO, offered))
    }

    @Test
    fun `session reports full resolution only for the largest uncropped size`() {
        val full = CaptureSessionInfo(
            lensFacing = LensFacing.BACK,
            configuredCaptureSize = PixelSize(4080, 3072),
            largestFourByThree = PixelSize(4080, 3072),
            cropIsFullFrame = true,
        )
        assertEquals(true, full.isFullResolution)
        assertEquals(false, full.copy(cropIsFullFrame = false).isFullResolution)
        assertEquals(false, full.copy(configuredCaptureSize = PixelSize(1920, 1440)).isFullResolution)
        assertNull(full.copy(largestFourByThree = null).isFullResolution)
    }

    @Test
    fun `EXIF orientation follows rotation, and the selfie mirror is applied after rotating`() {
        assertEquals(ExifOrientations.NORMAL, ExifOrientations.tagFor(0, false))
        assertEquals(ExifOrientations.ROTATE_90, ExifOrientations.tagFor(90, false))
        assertEquals(ExifOrientations.ROTATE_180, ExifOrientations.tagFor(180, false))
        assertEquals(ExifOrientations.ROTATE_270, ExifOrientations.tagFor(-90, false))
        assertEquals(ExifOrientations.FLIP_HORIZONTAL, ExifOrientations.tagFor(0, true))
        assertEquals(ExifOrientations.TRANSPOSE, ExifOrientations.tagFor(90, true))
        assertEquals(ExifOrientations.FLIP_VERTICAL, ExifOrientations.tagFor(180, true))
        assertEquals(ExifOrientations.TRANSVERSE, ExifOrientations.tagFor(270, true))
    }

    @Test
    fun `display-space mirror after a 90 degree rotation is a transpose`() {
        // Buffer pixel (x, y) in a W x H buffer. Rotating 90 cw sends it to (H-1-y, x) in an H-wide image;
        // mirroring that image left-right sends it to (y, x): the transpose, EXIF 5.
        val w = 4
        val h = 3
        for (x in 0 until w) for (y in 0 until h) {
            val (rx, ry) = (h - 1 - y) to x
            val mirrored = (h - 1 - rx) to ry
            assertEquals(y to x, mirrored)
        }
    }
}
