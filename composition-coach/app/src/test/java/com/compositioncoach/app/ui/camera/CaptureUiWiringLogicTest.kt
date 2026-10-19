package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.Steadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure decisions behind wiring the capture pipeline's state into the camera UI. */
class CaptureUiWiringLogicTest {

    // --- Steady hint -----------------------------------------------------------------------------------

    @Test
    fun `the hint is wanted while the pipeline holds the shutter, even when framing is not ready`() {
        assertTrue(SteadyHint.wanted(waitingForSteady = true, Steadiness.STEADY, shutterEngaged = false, shootReady = false))
    }

    @Test
    fun `otherwise the hint keeps its old rule - moving while engaged or ready`() {
        assertTrue(SteadyHint.wanted(false, Steadiness.MOVING, shutterEngaged = true, shootReady = false))
        assertTrue(SteadyHint.wanted(false, Steadiness.MOVING, shutterEngaged = false, shootReady = true))
        assertFalse(SteadyHint.wanted(false, Steadiness.MOVING, shutterEngaged = false, shootReady = false))
        assertFalse(SteadyHint.wanted(false, Steadiness.SLIGHT_MOTION, shutterEngaged = true, shootReady = true))
    }

    @Test
    fun `once shown the hint stays at least 500 ms`() {
        val shownAt = 1_000L
        assertTrue(SteadyHint.visible(wanted = false, shownAtMs = shownAt, nowMs = 1_100L))
        assertTrue(SteadyHint.visible(wanted = false, shownAtMs = shownAt, nowMs = 1_499L))
        assertFalse(SteadyHint.visible(wanted = false, shownAtMs = shownAt, nowMs = 1_500L))
        assertEquals(400L, SteadyHint.remainingHoldMs(shownAt, 1_100L))
        assertEquals(0L, SteadyHint.remainingHoldMs(shownAt, 9_000L))
    }

    @Test
    fun `a hint that was never shown has nothing to hold, and wanted always shows`() {
        assertFalse(SteadyHint.visible(wanted = false, shownAtMs = null, nowMs = 5L))
        assertEquals(0L, SteadyHint.remainingHoldMs(null, 5L))
        assertTrue(SteadyHint.visible(wanted = true, shownAtMs = null, nowMs = 5L))
    }

    // --- Coaching paused ---------------------------------------------------------------------------------

    @Test
    fun `the paused chip names the running enhancement only while analysis is paused`() {
        assertEquals("Coaching paused in Night", EnhancementCycle.pausedTextFor(CaptureEnhancement.NIGHT, true))
        assertNull(EnhancementCycle.pausedTextFor(CaptureEnhancement.NIGHT, false))
        // Off was just picked and the rebind has not landed yet: never "paused in Off".
        assertNull(EnhancementCycle.pausedTextFor(CaptureEnhancement.OFF, true))
    }

    @Test
    fun `the long-press menu notes which options pause coaching`() {
        val pausing = setOf(CaptureEnhancement.NIGHT)
        assertEquals("pauses coaching", EnhancementCycle.menuNote(CaptureEnhancement.NIGHT, pausing))
        assertNull(EnhancementCycle.menuNote(CaptureEnhancement.HDR, pausing))
        assertNull(EnhancementCycle.menuNote(CaptureEnhancement.OFF, emptySet()))
    }

    @Test
    fun `the chrome model carries the paused text from state`() {
        val state = CameraUiState(enhancement = CaptureEnhancement.HDR, analysisPausedByEnhancement = true)
        assertEquals("Coaching paused in HDR", CameraChromeModel.of(state).pausedText)
        assertNull(CameraChromeModel.of(state.copy(analysisPausedByEnhancement = false)).pausedText)
    }

    @Test
    fun `the chrome model dims the arc while moving`() {
        val state = CameraUiState(steadiness = Steadiness.MOVING)
        assertEquals(SteadyHint.MOVING_ARC_ALPHA, CameraChromeModel.of(state).arcAlpha)
    }

    // --- Zoom ----------------------------------------------------------------------------------------------

    private val presets = listOf(0.6f, 1f, 2f)

    @Test
    fun `the state's selected preset decides the lit chip`() {
        // The camera sits 4% over 2x: within the state's 5% but outside the display's 3% tolerance.
        assertEquals(2, ZoomPresets.litIndex(presets, selectedPreset = 2f, ratio = 2.08f))
        assertEquals(1, ZoomPresets.litIndex(presets, selectedPreset = 1f, ratio = 1f))
    }

    @Test
    fun `between presets the range under the live ratio is lit`() {
        assertEquals(1, ZoomPresets.litIndex(presets, selectedPreset = null, ratio = 1.4f))
        assertEquals(0, ZoomPresets.litIndex(presets, selectedPreset = null, ratio = 0.8f))
        // A selected preset that is not a chip (lens switch in flight) falls back to the range as well.
        assertEquals(2, ZoomPresets.litIndex(presets, selectedPreset = 5f, ratio = 3f))
    }

    @Test
    fun `a chip the camera sits on reads its own number`() {
        assertEquals("2×", ZoomPresets.chipLabel(2f, 2.08f, active = true, sittingOn = true))
        assertEquals("1.4×", ZoomPresets.chipLabel(1f, 1.4f, active = true, sittingOn = false))
    }

    @Test
    fun `selectedZoomPreset follows the live ratio in state, so pinch moves it`() {
        val state = CameraUiState(zoomPresets = presets, zoomRatio = 1f)
        assertEquals(1f, state.selectedZoomPreset)
        val pinched = state.withZoomState(1.5f, 0.6f, 10f)
        assertNull(pinched.selectedZoomPreset)
        assertEquals(2f, pinched.withZoomState(2f, 0.6f, 10f).selectedZoomPreset)
    }

    // --- Exposure --------------------------------------------------------------------------------------

    @Test
    fun `a rebind resets the exposure index the slider shows`() {
        val state = CameraUiState(exposureRange = -6..6).withExposureIndex(4)
        assertEquals(4, state.exposureIndex)
        val rebound = state.withCameraBound(state.lensFacing, hasFlashUnit = false, exposureRange = -6..6)
        assertEquals(0, rebound.exposureIndex)
        assertEquals(0.5f, ExposureText.fractionFor(rebound.exposureIndex, rebound.exposureRange))
    }
}
