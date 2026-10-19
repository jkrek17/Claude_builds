package com.compositioncoach.app.camera

import android.graphics.Bitmap
import android.graphics.Color
import com.compositioncoach.vision.StillImageAnalyzer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * ML Kit cannot run under Robolectric, which makes this the test of [StillImageAnalyzer]'s failure contract:
 * it must not throw, and must still return the working-size frame with image statistics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StillImageAnalyzerFallbackTest {

    @Test
    fun `detector failure yields a stats-only frame at the working size`() {
        val bitmap = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        for (y in 0 until 1500 step 10) for (x in 0 until 2000) bitmap.setPixel(x, y, if (y < 750) Color.WHITE else Color.DKGRAY)
        val frame = runBlocking { withTimeout(30_000) { StillImageAnalyzer().analyze(bitmap, isFrontCamera = true) } }
        assertEquals(1024, frame.frameWidth)
        assertEquals(768, frame.frameHeight)
        assertNotNull(frame.stats)
        assertTrue(frame.faces.isEmpty())
        assertTrue(frame.isFrontCamera)
        assertEquals(0, frame.deviceRotationDegrees)
        // The caller's bitmap is untouched (the analyzer works on its own copy).
        assertTrue(!bitmap.isRecycled)
    }
}
