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
    fun `long exposures read in seconds`() {
        assertEquals("1.5 s", CaptureDiagnostics(exposureTimeNs = 1_500_000_000L).shutterText)
    }
}
