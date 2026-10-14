package com.compositioncoach.app.camera

import android.view.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ImageCapture.targetRotation` must be the `Surface.ROTATION_*` constant that makes the saved JPEG
 * upright for the hold the shutter fired in — see [CaptureRotation]'s KDoc for why the mapping is the
 * identity on `deviceRotationDegrees` and why reusing Android's `OrientationEventListener` snippet
 * verbatim (which is clockwise-positive, the opposite sense) would swap 90 and 270.
 */
class CaptureRotationTest {

    @Test
    fun `portrait maps to ROTATION_0`() {
        assertEquals(Surface.ROTATION_0, CaptureRotation.surfaceRotationFor(0))
    }

    @Test
    fun `right edge up maps to ROTATION_90`() {
        // The task's named case. ROTATION_90 is defined as "rotated 90 degrees counter-clockwise from
        // natural", i.e. right edge up, which is the same sense DeviceRotationQuantizer reports in.
        assertEquals(Surface.ROTATION_90, CaptureRotation.surfaceRotationFor(90))
    }

    @Test
    fun `upside down maps to ROTATION_180`() {
        assertEquals(Surface.ROTATION_180, CaptureRotation.surfaceRotationFor(180))
    }

    @Test
    fun `left edge up maps to ROTATION_270`() {
        assertEquals(Surface.ROTATION_270, CaptureRotation.surfaceRotationFor(270))
    }

    @Test
    fun `90 and 270 are not swapped`() {
        // The single most likely way to get this wrong is to paste the clockwise-positive
        // OrientationEventListener mapping; that produces exactly this swap.
        org.junit.Assert.assertNotEquals(
            CaptureRotation.surfaceRotationFor(90),
            CaptureRotation.surfaceRotationFor(270),
        )
        assertEquals(1, CaptureRotation.surfaceRotationFor(90))
        assertEquals(3, CaptureRotation.surfaceRotationFor(270))
    }

    @Test
    fun `out-of-band values fall back to ROTATION_0 rather than throwing`() {
        assertEquals(Surface.ROTATION_0, CaptureRotation.surfaceRotationFor(45))
        assertEquals(Surface.ROTATION_0, CaptureRotation.surfaceRotationFor(-1))
        // Wrapped equivalents still resolve.
        assertEquals(Surface.ROTATION_90, CaptureRotation.surfaceRotationFor(450))
        assertEquals(Surface.ROTATION_270, CaptureRotation.surfaceRotationFor(-90))
    }
}
