package com.compositioncoach.app.ui.camera

import com.compositioncoach.composition.model.SmoothedComposition
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The debug overlay's `Rot:` line, plus the state path that feeds it.
 *
 * The state half matters as much as the string: chrome that "doesn't rotate" on a device is either a
 * wrong angle (covered by `RotationTruthTableTest` / `RotatedChromeRotationTest`) or a
 * `deviceRotationDegrees` that never leaves 0 — and the only way it reaches the UI is
 * `FrameAnalysis -> CameraViewModel.FrameUpdate -> withFrameUpdate -> CameraUiState`, so that reducer is
 * pinned here rather than left to a device to discover.
 */
class RotationDebugReadoutTest {

    @Test
    fun `the readout names every stage of the chain`() {
        val line = rotationDebugLine(
            deviceRotationDegrees = 90,
            analysisRotationDegrees = 0,
            chromeAngleDegrees = 90f,
            rollDegrees = -2.4f,
        )
        assertEquals("Rot: device=90 upright=0 chrome=90 roll=-2", line)
    }

    @Test
    fun `the readout matches the truth table in every hold, for the usual rear-camera mount`() {
        // Rear R0 = 90, so upright = 90 - device. Chrome is the clockwise Compose angle; at 270 atan2
        // reports the equivalent -90, which is what the line shows (and what the animation uses).
        assertEquals("Rot: device=0 upright=90 chrome=0 roll=0", rearLine(device = 0, upright = 90))
        assertEquals("Rot: device=90 upright=0 chrome=90 roll=0", rearLine(device = 90, upright = 0))
        assertEquals("Rot: device=180 upright=270 chrome=-180 roll=0", rearLine(device = 180, upright = 270))
        assertEquals("Rot: device=270 upright=180 chrome=-90 roll=0", rearLine(device = 270, upright = 180))
    }

    private fun rearLine(device: Int, upright: Int) = rotationDebugLine(
        deviceRotationDegrees = device,
        analysisRotationDegrees = upright,
        chromeAngleDegrees = OverlayMapper.uprightChromeAngleDegrees(device),
        rollDegrees = 0f,
    )

    @Test
    fun `withFrameUpdate carries the rotation chain from the frame onto the ui state`() {
        val updated = CameraUiState().withFrameUpdate(
            composition = SmoothedComposition.EMPTY,
            latencyMs = 12L,
            fps = 8f,
            samplingIntervalMs = 120L,
            engineTimeMs = 4L,
            deviceRotationDegrees = 270,
            analysisRotationDegrees = 180,
            rollDegrees = 3.5f,
        )
        assertEquals(270, updated.deviceRotationDegrees)
        assertEquals(180, updated.debugStats.analysisRotationDegrees)
        assertEquals(3.5f, updated.debugStats.rollDegrees, 1e-6f)
    }

    @Test
    fun `a later frame with a different rotation replaces the previous one rather than sticking`() {
        // Guards the "chrome never moves because the state never changes" failure mode: the reducer must
        // not, for instance, keep the first non-zero value or ignore a return to 0.
        var state = CameraUiState().withFrameUpdate(
            composition = SmoothedComposition.EMPTY,
            latencyMs = 0L,
            fps = 0f,
            samplingIntervalMs = 120L,
            engineTimeMs = 0L,
            deviceRotationDegrees = 90,
            analysisRotationDegrees = 0,
        )
        assertEquals(90, state.deviceRotationDegrees)
        state = state.withFrameUpdate(
            composition = SmoothedComposition.EMPTY,
            latencyMs = 0L,
            fps = 0f,
            samplingIntervalMs = 120L,
            engineTimeMs = 0L,
            deviceRotationDegrees = 0,
            analysisRotationDegrees = 90,
        )
        assertEquals(0, state.deviceRotationDegrees)
        assertEquals(90, state.debugStats.analysisRotationDegrees)
    }
}
