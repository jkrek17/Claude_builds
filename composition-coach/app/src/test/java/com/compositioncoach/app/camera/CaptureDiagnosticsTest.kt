package com.compositioncoach.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDiagnosticsTest {

    @Test
    fun `shutterText formats fractions and long exposures`() {
        assertEquals("1/120 s", CaptureDiagnostics(exposureTimeNs = 8_333_333L).shutterText)
        assertEquals("1/60 s", CaptureDiagnostics(exposureTimeNs = 16_666_667L).shutterText)
        assertEquals("1/4000 s", CaptureDiagnostics(exposureTimeNs = 250_000L).shutterText)
        assertEquals("1/2 s", CaptureDiagnostics(exposureTimeNs = 500_000_000L).shutterText)
        assertEquals("1.0 s", CaptureDiagnostics(exposureTimeNs = 1_000_000_000L).shutterText)
        assertEquals("2.5 s", CaptureDiagnostics(exposureTimeNs = 2_500_000_000L).shutterText)
        assertNull(CaptureDiagnostics().shutterText)
    }

    @Test
    fun `log line carries every field in a fixed order`() {
        val line = CaptureDiagnostics(
            widthPx = 4080,
            heightPx = 3072,
            iso = 100,
            exposureTimeNs = 8_333_333L,
            aperture = 1.68f,
            focalLengthMm = 6.9f,
            lensFacing = LensFacing.BACK,
            enhancement = CaptureEnhancement.OFF,
            captureMode = "MAXIMIZE_QUALITY+BURST3",
            jpegQuality = 100,
            steadiness = Steadiness.STEADY,
            waitedForSteadyMs = 0,
            zoomRatio = 1f,
            exposureCompensationIndex = 0,
            captureLatencyMs = 640,
            burstScores = listOf(120.5f, 140.25f, 99f),
            burstPicked = 1,
            autoLevelAppliedDegrees = -1.5f,
            configuredCaptureSize = "4080x3072",
            isFullResolution = true,
            noiseReductionMode = "HIGH_QUALITY",
            edgeMode = "HIGH_QUALITY",
            opticalStabilization = true,
        ).toLogLine()
        assertTrue(line, line.startsWith("capture size=4080x3072 configured=4080x3072 fullRes=true lens=BACK enhancement=OFF"))
        assertTrue(line, line.contains("shutter=1/120 s"))
        assertTrue(line, line.contains("burst=[120.5,140.3,99.0] picked=1"))
        assertTrue(line, line.contains("autoLevel=-1.50 latencyMs=640"))
        assertTrue(line, line.contains("nr=HIGH_QUALITY edge=HIGH_QUALITY ois=true"))
    }

    @Test
    fun `unknown values print as a dash`() {
        val line = CaptureDiagnostics().toLogLine()
        assertTrue(line, line.contains("iso=- shutter=-"))
        assertTrue(line, line.contains("burst=- picked=-"))
    }
}
