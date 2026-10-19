package com.compositioncoach.app.ui.camera

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageCapture
import com.compositioncoach.app.camera.AutoLevelGeometry
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.CapturePipeline
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.MotionSensor
import com.compositioncoach.app.camera.Steadiness
import com.compositioncoach.app.di.CaptureOrchestrationPort
import com.compositioncoach.app.outcome.CountdownController
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.engine.CompositionCoach
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.DeviceOrientation
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.compositioncoach.app.outcome.CapturePipeline as OutcomePipeline

/** What the shutter press saw, snapshotted by [CameraViewModel] at the moment the photo is taken. */
internal class ShutterContext(
    val controller: CameraController?,
    val imageCapture: ImageCapture,
    val shutterAtElapsedMs: Long,
    val frameAtShutter: FrameAnalysis?,
    val orientationAtShutter: DeviceOrientation?,
    /** The taste model the live coaching uses (user weights plus the outcome's focus). */
    val weights: AdaptiveWeights,
)

/**
 * The shutter half of [CameraViewModel]: the self-timer (with the Group hand-over to readiness) and the capture
 * itself, from the shutter press to the review hand-off.
 *
 * **Timer.** With `timerSeconds > 0` a press starts a [CountdownController] (a second press cancels it);
 * `CameraUiState.timerCountdown` shows 3, 2, 1 (the UI ticks a haptic per change), then 0 while a Group waits
 * for everyone (up to 15 s), then null as the capture starts.
 *
 * **Capture.** `isCapturing` -> (`isWaitingForSteady` while the gyro gate waits, at most 450 ms) -> the shutter
 * sound when required -> [CaptureOrchestrationPort.capture] for the outcome -> the live-frame evaluation ->
 * [ReviewFlowCoordinator.onCaptured] (the review entry is in the store) -> [CameraEvent.NavigateToReview] ->
 * auto-level, if it applies. A hold outcome's instruction shows from the press until the file is saved.
 */
internal class CaptureFlowCoordinator(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<CameraUiState>,
    private val coach: CompositionCoach,
    private val motionSensor: MotionSensor,
    private val capturePipeline: CapturePipeline,
    private val port: CaptureOrchestrationPort,
    private val reviewFlow: ReviewFlowCoordinator,
    private val levelling: PostCaptureLevelling,
    private val feedback: FeedbackTracker,
    private val emit: (CameraEvent) -> Unit,
    private val logDiagnostics: (CaptureDiagnostics) -> Unit,
    /** Runs after every capture attempt (the view model rebinds for an enhancement change deferred by it). */
    private val onCaptureSettled: () -> Unit,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val countdown = CountdownController()
    private var countdownJob: Job? = null

    val isCountingDown: Boolean get() = countdownJob?.isActive == true

    /**
     * The shutter was pressed. Starts the self-timer when one is set (a press during a countdown cancels it),
     * otherwise calls [fire] at once. [fire] snapshots the shutter context and calls [shoot].
     */
    fun onShutter(fire: () -> Unit) {
        if (isCountingDown) {
            cancelCountdown()
            return
        }
        val current = state.value
        if (current.isCapturing) return
        if (current.timerSeconds <= 0) {
            fire()
            return
        }
        countdown.start(clock(), current.timerSeconds, handOverToReadiness = current.outcome == Outcome.GROUP)
        state.update { it.withTimerCountdown(countdown.displayValue()) }
        countdownJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                val phase = countdown.tick(clock(), state.value.groupStatus?.ready == true)
                state.update { it.withTimerCountdown(countdown.displayValue(phase)) }
                if (phase == CountdownController.Phase.Fire) {
                    fire()
                    break
                }
                if (phase == CountdownController.Phase.Idle) break
            }
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        countdown.cancel()
        state.update { it.withTimerCountdown(null) }
    }

    /** Takes the photo now. Ignored while a capture is already running. */
    fun shoot(ctx: ShutterContext) {
        if (state.value.isCapturing) return
        val (cueAtCapture, followed) = feedback.onCapture()
        state.update { it.withCaptureStarted() }
        scope.launch {
            try {
                val gateNeeded = motionSensor.steadiness.value == Steadiness.MOVING
                if (gateNeeded) state.update { it.withWaitingForSteady(true) }
                val (steadiness, waitedMs) = capturePipeline.awaitSteady()
                if (gateNeeded) state.update { it.withWaitingForSteady(false) }

                val current = state.value
                val outcome = current.outcome
                val controller = ctx.controller
                val session = controller?.session
                val lensFacing = session?.lensFacing ?: current.lensFacing
                val request = CapturePipeline.ShotRequest(
                    controller = controller,
                    imageCapture = ctx.imageCapture,
                    lensFacing = lensFacing,
                    session = session,
                    burst = current.burstSharpestActive && session?.activeEnhancement == CaptureEnhancement.OFF &&
                        outcome.spec.pipeline != OutcomePipeline.SINGLE,
                    zoomRatio = controller?.currentZoomRatio ?: current.zoomRatio,
                    exposureIndex = current.exposureIndex,
                    shutterAtElapsedMs = ctx.shutterAtElapsedMs,
                    steadiness = steadiness,
                    waitedForSteadyMs = waitedMs,
                )
                port.playShutterSound(current.settings.shutterSound)
                val shot = port.capture(outcome, request)
                val intent = current.settings.sceneIntent
                val live = withContext(Dispatchers.Default) {
                    ctx.frameAtShutter?.let { coach.evaluateOnce(it, GuidanceLevel.COACH, intent, ctx.weights) }
                        ?: CompositionResult.empty()
                }
                val orientation = ctx.orientationAtShutter
                val levels = current.settings.autoLevel && orientation != null &&
                    AutoLevelGeometry.shouldLevel(orientation.rollDegrees, orientation.isReliable)
                reviewFlow.onCaptured(
                    CapturedShot(
                        uri = shot.uri,
                        diagnostics = shot.diagnostics,
                        liveResult = live,
                        liveFrame = ctx.frameAtShutter,
                        isFrontCamera = lensFacing == LensFacing.FRONT,
                        outcome = outcome,
                        intent = intent,
                        weights = ctx.weights,
                        cueAtCapture = cueAtCapture,
                        followedSinceLastCapture = followed,
                        levelling = levels,
                    ),
                )
                state.update { it.withCaptureFinished().withLastPhotoUri(shot.uri) }
                clearPostCaptureFadeAfterDelay()
                emit(CameraEvent.NavigateToReview)
                if (levels) levelling.run(shot.uri, shot.diagnostics, orientation!!) else logDiagnostics(shot.diagnostics)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Capture failed", t)
                state.update { it.withCaptureError("Couldn't save photo: ${t.message ?: "unknown error"}") }
            } finally {
                onCaptureSettled()
            }
        }
    }

    /** Ends the score-badge/banner post-capture fade ~1 s after it started, see [CameraUiState.postCaptureFadeActive]. */
    private fun clearPostCaptureFadeAfterDelay() {
        scope.launch {
            delay(POST_CAPTURE_FADE_MS)
            state.update { it.withPostCaptureFadeEnded() }
        }
    }

    private companion object {
        const val TAG = "CaptureFlow"
        const val TICK_MS = 100L
        const val POST_CAPTURE_FADE_MS = 1_000L
    }
}
