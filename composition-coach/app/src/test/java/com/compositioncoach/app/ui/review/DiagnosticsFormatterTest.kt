package com.compositioncoach.app.ui.review

import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.Steadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsFormatterTest {

    private val full = CaptureDiagnostics(
        widthPx = 4000,
        heightPx = 3000,
        iso = 160,
        exposureTimeNs = 8_333_333L,
        aperture = 1.85f,
        focalLengthMm = 6.81f,
        lensFacing = LensFacing.BACK,
        enhancement = CaptureEnhancement.NIGHT,
        captureMode = "MAXIMIZE_QUALITY",
        jpegQuality = 95,
        steadiness = Steadiness.STEADY,
        waitedForSteadyMs = 240,
        zoomRatio = 2f,
        exposureCompensationIndex = 2,
        captureLatencyMs = 412,
    )

    @Test
    fun `summary reads like the spec's example`() {
        assertEquals("12 MP · 1/120 s · ISO 160 · Night · steady", DiagnosticsFormatter.summary(full))
    }

    @Test
    fun `summary omits whatever the device did not report`() {
        val partial = CaptureDiagnostics(iso = 800, steadiness = Steadiness.MOVING)
        assertEquals("ISO 800 · moving", DiagnosticsFormatter.summary(partial))
    }

    @Test
    fun `summary leaves out an enhancement that was off`() {
        val d = CaptureDiagnostics(widthPx = 4032, heightPx = 3024, enhancement = CaptureEnhancement.OFF)
        assertEquals("12 MP", DiagnosticsFormatter.summary(d))
    }

    @Test
    fun `an all-unknown capture has no summary at all`() {
        assertNull(DiagnosticsFormatter.summary(CaptureDiagnostics()))
    }

    @Test
    fun `megapixels round to whole numbers from 10 MP and keep a decimal below`() {
        assertEquals("50 MP", DiagnosticsFormatter.megapixels(8160, 6120))
        assertEquals("8.3 MP", DiagnosticsFormatter.megapixels(3328, 2496))
        assertEquals("1.9 MP", DiagnosticsFormatter.megapixels(1600, 1200))
        assertEquals("2 MP", DiagnosticsFormatter.megapixels(2000, 1000))
        assertNull(DiagnosticsFormatter.megapixels(4000, null))
        assertNull(DiagnosticsFormatter.megapixels(0, 3000))
    }

    @Test
    fun `details list every reported field in EXIF-panel order`() {
        val rows = DiagnosticsFormatter.details(full).map { it.label to it.value }
        assertEquals(
            listOf(
                "Resolution" to "4000 × 3000 · 12 MP",
                "Shutter" to "1/120 s",
                "ISO" to "160",
                "Aperture" to "f/1.9",
                "Focal length" to "6.8 mm",
                "Lens" to "Back",
                "Enhancement" to "Night",
                "Capture mode" to "Maximize quality",
                "JPEG quality" to "95",
                "Steadiness" to "Steady · waited 240 ms",
                "Zoom" to "2.0×",
                "Exposure comp." to "+2 steps",
                "Shutter latency" to "412 ms",
            ),
            rows,
        )
    }

    @Test
    fun `details of an unknown capture still state the enhancement`() {
        assertEquals(listOf("Enhancement" to "Off"), DiagnosticsFormatter.details(CaptureDiagnostics()).map { it.label to it.value })
    }

    @Test
    fun `whole-number apertures and focal lengths drop the trailing zero`() {
        val rows = DiagnosticsFormatter.details(CaptureDiagnostics(aperture = 2.0f, focalLengthMm = 26f)).associate { it.label to it.value }
        assertEquals("f/2", rows["Aperture"])
        assertEquals("26 mm", rows["Focal length"])
    }

    @Test
    fun `steadiness detail omits a zero wait and survives a missing reading`() {
        assertEquals("Slight motion", DiagnosticsFormatter.steadinessDetail(Steadiness.SLIGHT_MOTION, 0))
        assertEquals("Waited 120 ms", DiagnosticsFormatter.steadinessDetail(null, 120))
        assertNull(DiagnosticsFormatter.steadinessDetail(null, null))
    }

    @Test
    fun `exposure steps are signed and pluralised`() {
        assertEquals("0", DiagnosticsFormatter.exposureSteps(0))
        assertEquals("+1 step", DiagnosticsFormatter.exposureSteps(1))
        assertEquals("-3 steps", DiagnosticsFormatter.exposureSteps(-3))
    }

    @Test
    fun `pipeline rows appear in place when the capture reports them`() {
        val d = full.copy(
            captureMode = "MAXIMIZE_QUALITY+BURST3",
            configuredCaptureSize = "4080x3072",
            isFullResolution = true,
            opticalStabilization = true,
            burstScores = listOf(812.4f, 901.7f, 655f),
            burstPicked = 1,
            noiseReductionMode = "HIGH_QUALITY",
            edgeMode = "ZSL",
            autoLevelAppliedDegrees = -1.4f,
        )
        val rows = DiagnosticsFormatter.details(d).map { it.label to it.value }
        assertEquals(
            listOf(
                "Resolution" to "4000 × 3000 · 12 MP",
                "Capture size" to "4080 × 3072 · full resolution",
                "Shutter" to "1/120 s",
                "ISO" to "160",
                "Aperture" to "f/1.9",
                "Focal length" to "6.8 mm",
                "Lens" to "Back",
                "Stabilization" to "OIS on",
                "Enhancement" to "Night",
                "Capture mode" to "Maximize quality+burst3",
                "Burst" to "Kept 2 of 3 · 812, 902, 655",
                "JPEG quality" to "95",
                "Noise reduction" to "High quality",
                "Sharpening" to "Zero shutter lag",
                "Steadiness" to "Steady · waited 240 ms",
                "Zoom" to "2.0×",
                "Exposure comp." to "+2 steps",
                "Auto-level" to "1.4° counter-clockwise",
                "Shutter latency" to "412 ms",
            ),
            rows,
        )
    }

    @Test
    fun `capture size survives either half missing`() {
        assertEquals("4080 × 3072 · not full resolution", DiagnosticsFormatter.captureSize("4080x3072", false))
        assertEquals("4080 × 3072", DiagnosticsFormatter.captureSize("4080x3072", null))
        assertEquals("Full resolution", DiagnosticsFormatter.captureSize(null, true))
        assertNull(DiagnosticsFormatter.captureSize(null, null))
    }

    @Test
    fun `burst without a pick still lists the scores, a single shot has no row`() {
        assertEquals("10, 20", DiagnosticsFormatter.burst(listOf(10f, 20f), null))
        assertNull(DiagnosticsFormatter.burst(null, 0))
        assertNull(DiagnosticsFormatter.burst(emptyList(), null))
    }

    @Test
    fun `auto-level reads its direction and OIS off is stated`() {
        assertEquals("0.8° clockwise", DiagnosticsFormatter.autoLevel(0.8f))
        val rows = DiagnosticsFormatter.details(CaptureDiagnostics(opticalStabilization = false)).associate { it.label to it.value }
        assertEquals("OIS off", rows["Stabilization"])
    }

    @Test
    fun `long exposures read in seconds`() {
        assertEquals("1.5 s", CaptureDiagnostics(exposureTimeNs = 1_500_000_000L).shutterText)
    }
}
