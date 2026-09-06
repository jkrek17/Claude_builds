package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.camera.FlashMode
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.SmoothedComposition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiStateTest {

    private fun shootReadyComposition(ready: Boolean) = SmoothedComposition(
        displayScore = if (ready) 95 else 60,
        activeRecommendations = emptyList(),
        isShootReady = ready,
        scene = com.compositioncoach.composition.model.SceneClassification.UNKNOWN,
        primarySubject = null,
        raw = CompositionResult.empty(),
    )

    private fun awaitingSubjectComposition() = SmoothedComposition(
        displayScore = 60,
        activeRecommendations = emptyList(),
        isShootReady = false,
        scene = com.compositioncoach.composition.model.SceneClassification.UNKNOWN,
        primarySubject = null,
        raw = CompositionResult.empty(),
        awaitingSubject = true,
    )

    @Test
    fun `withSettings replaces only the settings field`() {
        val state = CameraUiState()
        val updated = state.withSettings(CoachSettings(debugMode = true))
        assertTrue(updated.settings.debugMode)
        assertEquals(state.composition, updated.composition)
    }

    @Test
    fun `withCameraBound clears any previous camera error`() {
        val state = CameraUiState(cameraError = "boom")
        val updated = state.withCameraBound(LensFacing.FRONT, hasFlashUnit = true)
        assertNull(updated.cameraError)
        assertEquals(LensFacing.FRONT, updated.lensFacing)
        assertTrue(updated.hasFlashUnit)
    }

    @Test
    fun `withCameraError sets the message without touching capture state`() {
        val state = CameraUiState(isCapturing = true)
        val updated = state.withCameraError("no camera")
        assertEquals("no camera", updated.cameraError)
        assertTrue(updated.isCapturing)
    }

    @Test
    fun `withFlashMode updates the flash mode`() {
        val updated = CameraUiState().withFlashMode(FlashMode.ON)
        assertEquals(FlashMode.ON, updated.flashMode)
    }

    @Test
    fun `capture lifecycle clears errors on start and stops capturing on finish or error`() {
        val started = CameraUiState(captureError = "old error").withCaptureStarted()
        assertTrue(started.isCapturing)
        assertNull(started.captureError)

        val finished = started.withCaptureFinished()
        assertFalse(finished.isCapturing)

        val failed = started.withCaptureError("disk full")
        assertFalse(failed.isCapturing)
        assertEquals("disk full", failed.captureError)
    }

    @Test
    fun `withCaptureFinished starts the post-capture fade`() {
        val state = CameraUiState(postCaptureFadeActive = false).withCaptureStarted()
        assertFalse(state.postCaptureFadeActive)

        val finished = state.withCaptureFinished()
        assertTrue(finished.postCaptureFadeActive)
        assertFalse(finished.isCapturing)
    }

    @Test
    fun `withPostCaptureFadeEnded clears the fade without touching other fields`() {
        val faded = CameraUiState(postCaptureFadeActive = true, flashMode = FlashMode.ON)
        val ended = faded.withPostCaptureFadeEnded()
        assertFalse(ended.postCaptureFadeActive)
        assertEquals(FlashMode.ON, ended.flashMode)
    }

    @Test
    fun `a capture error cancels an in-progress post-capture fade`() {
        val faded = CameraUiState(postCaptureFadeActive = true, isCapturing = true)
        val failed = faded.withCaptureError("disk full")
        assertFalse(failed.postCaptureFadeActive)
        assertFalse(failed.isCapturing)
    }

    @Test
    fun `withFrameUpdate replaces composition and debug stats together`() {
        val updated = CameraUiState().withFrameUpdate(
            composition = shootReadyComposition(ready = false),
            latencyMs = 42L,
            fps = 9.5f,
            samplingIntervalMs = 100L,
            engineTimeMs = 7L,
            detectorTimings = mapOf("face" to 3L),
        )
        assertEquals(60, updated.composition.displayScore)
        assertEquals(42L, updated.debugStats.lastLatencyMs)
        assertEquals(9.5f, updated.debugStats.fps)
        assertEquals(7L, updated.debugStats.engineTimeMs)
        assertEquals(mapOf("face" to 3L), updated.debugStats.detectorTimings)
    }

    @Test
    fun `withFrameUpdate carries the composition's awaitingSubject flag through untouched`() {
        val updated = CameraUiState().withFrameUpdate(
            composition = awaitingSubjectComposition(),
            latencyMs = 10L,
            fps = 8f,
            samplingIntervalMs = 100L,
            engineTimeMs = 5L,
        )
        assertTrue(updated.composition.awaitingSubject)
        assertEquals(60, updated.composition.displayScore)

        val notAwaiting = CameraUiState().withFrameUpdate(
            composition = shootReadyComposition(ready = false),
            latencyMs = 10L,
            fps = 8f,
            samplingIntervalMs = 100L,
            engineTimeMs = 5L,
        )
        assertFalse(notAwaiting.composition.awaitingSubject)
    }

    @Test
    fun `justEnteredShootReady is true only on the not-ready to ready transition`() {
        val notReady = CameraUiState(composition = shootReadyComposition(ready = false))
        val ready = CameraUiState(composition = shootReadyComposition(ready = true))

        assertTrue(ready.justEnteredShootReady(previous = notReady))
        assertFalse(ready.justEnteredShootReady(previous = ready))
        assertFalse(notReady.justEnteredShootReady(previous = ready))
        assertFalse(notReady.justEnteredShootReady(previous = notReady))
    }
}
