package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.camera.CaptureEnhancement.AUTO
import com.compositioncoach.app.camera.CaptureEnhancement.HDR
import com.compositioncoach.app.camera.CaptureEnhancement.NIGHT
import com.compositioncoach.app.camera.CaptureEnhancement.OFF
import com.compositioncoach.app.camera.Steadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enhancement-pill cycling, zoom-chip selection, exposure EV text and the steady hint. */
class ImageQualityControlsLogicTest {

    // --- Enhancement pill ----------------------------------------------------------------------------

    @Test
    fun `the pill is hidden unless something beyond Off is available`() {
        assertFalse(EnhancementCycle.isVisible(emptySet()))
        assertFalse(EnhancementCycle.isVisible(setOf(OFF)))
        assertTrue(EnhancementCycle.isVisible(setOf(NIGHT)))
        assertEquals(emptyList<Any>(), EnhancementCycle.options(setOf(OFF)))
    }

    @Test
    fun `options keep enum order and always include Off`() {
        assertEquals(listOf(OFF, AUTO, HDR, NIGHT), EnhancementCycle.options(setOf(NIGHT, HDR, AUTO)))
        assertEquals(listOf(OFF, NIGHT), EnhancementCycle.options(setOf(NIGHT)))
    }

    @Test
    fun `a tap cycles through the available options and wraps`() {
        val all = setOf(AUTO, HDR, NIGHT)
        assertEquals(HDR, EnhancementCycle.next(AUTO, all))
        assertEquals(NIGHT, EnhancementCycle.next(HDR, all))
        assertEquals(OFF, EnhancementCycle.next(NIGHT, all))
        assertEquals(AUTO, EnhancementCycle.next(OFF, all))
    }

    @Test
    fun `a tap skips what this lens does not offer`() {
        assertEquals(NIGHT, EnhancementCycle.next(OFF, setOf(NIGHT)))
        assertEquals(OFF, EnhancementCycle.next(NIGHT, setOf(NIGHT)))
    }

    @Test
    fun `a current value the lens no longer offers restarts at the first real enhancement`() {
        assertEquals(AUTO, EnhancementCycle.next(NIGHT, setOf(AUTO, HDR)))
        assertEquals(OFF, EnhancementCycle.next(HDR, emptySet()))
    }

    @Test
    fun `pill wording`() {
        assertEquals("Night", EnhancementCycle.label(NIGHT))
        assertEquals("Coaching paused in Night", EnhancementCycle.pausedText(NIGHT))
    }

    // --- Zoom chips ----------------------------------------------------------------------------------

    private val presets = listOf(0.6f, 1f, 2f, 5f)

    @Test
    fun `the row is hidden with a single preset`() {
        assertFalse(ZoomPresets.isVisible(listOf(1f)))
        assertTrue(ZoomPresets.isVisible(presets))
    }

    @Test
    fun `the lit chip is the largest preset at or below the ratio`() {
        assertEquals(0, ZoomPresets.activeIndex(presets, 0.6f))
        assertEquals(0, ZoomPresets.activeIndex(presets, 0.8f))
        assertEquals(1, ZoomPresets.activeIndex(presets, 1f))
        assertEquals(1, ZoomPresets.activeIndex(presets, 1.4f))
        assertEquals(2, ZoomPresets.activeIndex(presets, 2f))
        assertEquals(3, ZoomPresets.activeIndex(presets, 8f))
    }

    @Test
    fun `a ratio a hair under a preset still lights it, and below the first lights the first`() {
        assertEquals(2, ZoomPresets.activeIndex(presets, 1.98f))
        assertEquals(0, ZoomPresets.activeIndex(presets, 0.5f))
        assertEquals(-1, ZoomPresets.activeIndex(emptyList(), 1f))
    }

    @Test
    fun `chip labels follow the Pixel convention`() {
        assertEquals("0.6", ZoomPresets.chipLabel(0.6f, 1f, active = false))
        assertEquals("2", ZoomPresets.chipLabel(2f, 1f, active = false))
        assertEquals("1×", ZoomPresets.chipLabel(1f, 1f, active = true))
        assertEquals("1.4×", ZoomPresets.chipLabel(1f, 1.4f, active = true))
        assertEquals("Zoom 0.6×", ZoomPresets.chipDescription(0.6f))
    }

    // --- Exposure ------------------------------------------------------------------------------------

    @Test
    fun `EV text is signed with one decimal`() {
        assertEquals("+0.7", ExposureText.format(2, 1f / 3f))
        assertEquals("-1.3", ExposureText.format(-4, 1f / 3f))
        assertEquals("0.0", ExposureText.format(0, 1f / 6f))
        assertEquals("+2.0", ExposureText.format(12, 1f / 6f))
        assertEquals("Exposure plus 0.7", ExposureText.description(2, 1f / 3f))
        assertEquals("Exposure normal", ExposureText.description(0, 1f / 3f))
    }

    @Test
    fun `an empty range is not adjustable`() {
        assertFalse(ExposureText.isAdjustable(0..0))
        assertTrue(ExposureText.isAdjustable(-6..6))
    }

    @Test
    fun `fallback step sizes reproduce the usual two-stop ranges`() {
        assertEquals(1f / 3f, ExposureText.fallbackStepEv(-6..6), 1e-6f)
        assertEquals(1f / 6f, ExposureText.fallbackStepEv(-12..12), 1e-6f)
        assertEquals(1f / 10f, ExposureText.fallbackStepEv(-20..20), 1e-6f)
        assertEquals(0f, ExposureText.fallbackStepEv(0..0), 0f)
    }

    @Test
    fun `slider fraction and index round-trip`() {
        val range = -6..6
        assertEquals(0f, ExposureText.fractionFor(-6, range), 1e-6f)
        assertEquals(0.5f, ExposureText.fractionFor(0, range), 1e-6f)
        assertEquals(1f, ExposureText.fractionFor(6, range), 1e-6f)
        assertEquals(2, ExposureText.indexFor(ExposureText.fractionFor(2, range), range))
        assertEquals(6, ExposureText.indexFor(1.5f, range))
        assertEquals(0, ExposureText.indexFor(0.5f, 0..0))
    }

    // --- Steady hint ---------------------------------------------------------------------------------

    @Test
    fun `hold still shows only when moving at the moment that matters`() {
        assertFalse(SteadyHint.showsHoldStill(Steadiness.MOVING, shutterEngaged = false, shootReady = false))
        assertTrue(SteadyHint.showsHoldStill(Steadiness.MOVING, shutterEngaged = true, shootReady = false))
        assertTrue(SteadyHint.showsHoldStill(Steadiness.MOVING, shutterEngaged = false, shootReady = true))
        assertFalse(SteadyHint.showsHoldStill(Steadiness.SLIGHT_MOTION, shutterEngaged = true, shootReady = true))
    }

    @Test
    fun `the arc dims to 70 percent while moving, but awaiting-subject wins`() {
        assertEquals(1f, SteadyHint.arcAlpha(awaitingSubject = false, steadiness = Steadiness.STEADY), 0f)
        assertEquals(0.7f, SteadyHint.arcAlpha(awaitingSubject = false, steadiness = Steadiness.MOVING), 0f)
        assertEquals(
            GuidanceFormatter.AWAITING_SUBJECT_ARC_ALPHA,
            SteadyHint.arcAlpha(awaitingSubject = true, steadiness = Steadiness.MOVING),
            0f,
        )
    }

    @Test
    fun `the peak needs ready framing and a phone that is not moving`() {
        assertTrue(SteadyHint.peakReached(shootReady = true, steadiness = Steadiness.SLIGHT_MOTION))
        assertFalse(SteadyHint.peakReached(shootReady = true, steadiness = Steadiness.MOVING))
        assertFalse(SteadyHint.peakReached(shootReady = false, steadiness = Steadiness.STEADY))
    }
}
