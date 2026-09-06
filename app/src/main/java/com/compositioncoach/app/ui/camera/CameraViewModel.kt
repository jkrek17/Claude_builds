package com.compositioncoach.app.ui.camera

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compositioncoach.app.camera.CameraBindResult
import com.compositioncoach.app.camera.FlashMode
import com.compositioncoach.app.camera.FrameFpsTracker
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.toIntRange
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.vision.VisionFeatureToggles
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus

/** One-shot UI events the screen reacts to but that don't belong in persistent state. */
sealed interface CameraEvent {
    data object NavigateToReview : CameraEvent
    data object ShootReadyHapticTick : CameraEvent
}

/**
 * Owns the camera screen's state. Consumes [AppContainer.frameSourceOrNull]'s frame flow and
 * [AppContainer.coach] to keep [uiState] in sync with the live preview; per-frame work runs on
 * [Dispatchers.Default] since detector output and scoring are CPU-bound, not I/O.
 *
 * Frame handling in short: every analyzed [FrameAnalysis] is scored immediately (so the temporal smoother
 * in [AppContainer.coach] sees every frame and stays stable), but the *UI* only redraws at ~10 Hz via
 * [sample] — the coach can process frames faster than the screen needs to repaint.
 */
@OptIn(FlowPreview::class)
class CameraViewModel(private val container: AppContainer) : ViewModel() {

    private val frameSource = container.frameSourceOrNull()
    private val coach = container.coach
    private val thermalPolicy = container.thermalPolicy

    /** Bound to CameraX's `ImageAnalysis` by the screen; null when the vision pipeline is unavailable. */
    val frameAnalyzer: ImageAnalysis.Analyzer? get() = frameSource?.imageAnalyzer

    /** Volume-down key presses, forwarded from [com.compositioncoach.app.MainActivity.onKeyDown]. */
    val volumeDownEvents: SharedFlow<Unit> get() = container.volumeDownEvents

    private val _uiState = MutableStateFlow(CameraUiState(analysisUnavailable = frameSource == null))
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CameraEvent> = _events

    @Volatile private var lastFrame: FrameAnalysis? = null

    private val fpsTracker = FrameFpsTracker()
    @Volatile private var currentFps = 0f

    /** Null until the first settings emission; used to reset smoothing only on an actual mode change, not on startup. */
    private var lastSceneIntent: SceneIntent? = null

    /** Whether [resumePipeline]/[pausePipeline] currently consider the pipeline running; makes both idempotent. */
    @Volatile private var pipelineActive = false

    /**
     * Last-resort net for the frame-processing pipeline: logs and lets [viewModelScope] carry on rather
     * than letting an uncaught exception here crash the app (the per-frame `try`/`catch` in
     * [observeFrames] is the primary defense and is what actually keeps *coaching* alive frame-to-frame;
     * this only guards the coroutine machinery around it).
     */
    private val frameProcessingExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Frame processing pipeline failed; camera preview keeps running", throwable)
    }

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _uiState.update { it.withSettings(settings) }
                frameSource?.setPoseDetectionEnabled(settings.poseDetectionEnabled)
                frameSource?.setTargetIntervalMs(settings.analysisIntervalMs)
                val toggles = frameSource as? VisionFeatureToggles
                toggles?.setObjectDetectionEnabled(settings.detectObjectsEnabled)
                toggles?.setSegmentationEnabled(settings.effectiveSubjectMaskEnabled)
                // Shooting mode changed: reset the smoother so advice/scoring from the old mode doesn't linger.
                if (lastSceneIntent != null && lastSceneIntent != settings.sceneIntent) {
                    coach.reset()
                }
                lastSceneIntent = settings.sceneIntent
            }
        }
        // Device-health-driven cadence tier (see ThermalPolicy's KDoc): applied to the vision pipeline's
        // own cadence on top of (never instead of) the settings-driven toggles above, and surfaced in
        // CameraUiState.performanceTier for the debug overlay. VisionPipeline itself combines this with
        // the settings-driven analysis interval (maxOf, the slower of the two always wins), so this
        // collector only needs to forward the raw tier, not compute an effective interval itself.
        viewModelScope.launch {
            thermalPolicy.tier.collect { tier ->
                _uiState.update { it.withPerformanceTier(tier) }
                (frameSource as? VisionFeatureToggles)?.setPerformanceTier(tier)
            }
        }
        // Restore the last saved photo's Uri on a fresh process start (CameraUiState.lastPhotoUri is
        // otherwise only set by a capture happening this process) so a gallery-shortcut button has
        // something to show immediately rather than waiting for the user's next capture.
        viewModelScope.launch {
            container.captureRepository.latestPhotoUri()?.let { uri -> _uiState.update { it.withLastPhotoUri(uri) } }
        }
        observeFrames()
    }

    private fun observeFrames() {
        val source = frameSource ?: return
        source.frames
            .onEach { frame -> lastFrame = frame; currentFps = fpsTracker.onFrame(frame.timestampNanos) }
            .map { frame ->
                val settings = _uiState.value.settings
                val composition = if (settings.guidanceEnabled) {
                    // A detector/engine exception on one frame must not stop coaching for every frame
                    // after it: catch here (inside the per-frame map, not around the whole flow) so a
                    // bad frame is skipped — the previous composition keeps showing — and the flow
                    // keeps collecting. frameProcessingExceptionHandler below is the last-resort net for
                    // anything that still escapes this (e.g. from onEach/the FPS tracker).
                    try {
                        coach.process(frame, settings.guidanceLevel, settings.sceneIntent)
                    } catch (t: Throwable) {
                        Log.e(TAG, "Composition engine threw while scoring a frame; keeping the last result", t)
                        _uiState.value.composition
                    }
                } else {
                    SmoothedComposition.EMPTY
                }
                FrameUpdate(
                    composition = composition,
                    latencyMs = frame.analysisLatencyMs,
                    samplingIntervalMs = settings.analysisIntervalMs,
                    detectorTimings = frame.detectorTimings,
                    debugFrame = DebugFrameData(objects = frame.objects, subjectMask = frame.subjectMask),
                    deviceRotationDegrees = frame.deviceRotationDegrees,
                )
            }
            .flowOn(Dispatchers.Default)
            .sample(UI_UPDATE_INTERVAL_MS)
            .onEach(::applyFrameUpdate)
            .launchIn(viewModelScope + frameProcessingExceptionHandler)
    }

    private fun applyFrameUpdate(update: FrameUpdate) {
        val previous = _uiState.value
        _uiState.update {
            it.withFrameUpdate(
                composition = update.composition,
                latencyMs = update.latencyMs,
                fps = currentFps,
                samplingIntervalMs = update.samplingIntervalMs,
                engineTimeMs = update.composition.raw.engineTimeMs,
                detectorTimings = update.detectorTimings,
                debugFrame = update.debugFrame,
                deviceRotationDegrees = update.deviceRotationDegrees,
            )
        }
        if (_uiState.value.justEnteredShootReady(previous)) {
            _events.tryEmit(CameraEvent.ShootReadyHapticTick)
        }
    }

    // --- Camera lifecycle & controls -------------------------------------------------------------------

    /**
     * Call when the camera screen enters composition. Idempotent (see [resumePipeline]) — safe to call
     * alongside [onScreenResumed] without double-starting the pipeline.
     */
    fun onScreenStarted() = resumePipeline()

    /** Call when the camera screen leaves composition. Idempotent, mirrors [onScreenStarted]. */
    fun onScreenStopped() = pausePipeline()

    /**
     * Activity-lifecycle hook: wire to `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` on the camera
     * screen so the sensor/detector lifecycle tracks the *Activity* going to/from the background, not
     * just Compose entering/leaving composition. Before this hook existed, backgrounding the app left
     * [frameSource]'s orientation sensor registered and its ML Kit detectors allocated indefinitely,
     * since the screen's `DisposableEffect(viewModel)` only fires on composition entry/exit, which
     * doesn't happen on a simple app-switch. Idempotent — see [resumePipeline].
     */
    fun onScreenResumed() = resumePipeline()

    /** Activity-lifecycle counterpart to [onScreenResumed]; wire to `LifecycleEventEffect(ON_PAUSE)`. */
    fun onScreenPaused() = pausePipeline()

    /**
     * Starts [frameSource] and [thermalPolicy], and resets the coach smoother, unless already active.
     * Safe to call from both the composition-entry and Activity-resume hooks above without double work.
     */
    private fun resumePipeline() {
        if (pipelineActive) return
        pipelineActive = true
        frameSource?.start()
        thermalPolicy.start()
        coach.reset()
    }

    /** Stops [frameSource] and [thermalPolicy] unless already inactive. Mirrors [resumePipeline]. */
    private fun pausePipeline() {
        if (!pipelineActive) return
        pipelineActive = false
        frameSource?.stop()
        thermalPolicy.stop()
    }

    fun onSwitchLensRequested() {
        val next = if (_uiState.value.lensFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
        // Tell the pipeline about the mirror change *before* the rebind so no frame from the new lens is
        // normalized with the old mirroring; onCameraBindResult corrects it if the device fell back.
        frameSource?.setFrontCamera(next == LensFacing.FRONT)
        coach.reset()
        _uiState.update { it.copy(lensFacing = next) }
    }

    /** Called by the screen once a (re)bind attempt finishes; wires the result into pipeline state and resets smoothing. */
    fun onCameraBindResult(result: CameraBindResult) {
        when (result) {
            is CameraBindResult.Success -> {
                frameSource?.setFrontCamera(result.lensFacing == LensFacing.FRONT)
                coach.reset()
                _uiState.update { it.withCameraBound(result.lensFacing, result.hasFlashUnit, result.exposureRange.toIntRange()) }
            }
            is CameraBindResult.Failure -> {
                _uiState.update { it.withCameraError(result.throwable.message ?: "Camera unavailable") }
            }
        }
    }

    fun onFlashModeChanged(mode: FlashMode) {
        _uiState.update { it.withFlashMode(mode) }
    }

    /**
     * Records the exposure-compensation index a slider (or similar control) just applied via
     * `CameraController.setExposureCompensation` — this method only updates the slider's displayed
     * position in [uiState]; the actual hardware call is made by the screen against its own
     * `CameraController` instance, the same division of responsibility [onFlashModeChanged] already
     * follows for `cycleFlashMode`.
     */
    fun onExposureCompensationChanged(index: Int) {
        _uiState.update { it.withExposureIndex(index) }
    }

    fun onCaptureError(message: String) {
        _uiState.update { it.withCaptureError(message) }
    }

    fun dismissCameraError() {
        _uiState.update { it.copy(cameraError = null) }
    }

    // --- Capture -----------------------------------------------------------------------------------------

    fun onShutterClick(imageCapture: ImageCapture?) {
        val capture = imageCapture ?: return
        if (_uiState.value.isCapturing) return
        val isFrontCamera = _uiState.value.lensFacing == LensFacing.FRONT
        _uiState.update { it.withCaptureStarted() }
        viewModelScope.launch {
            try {
                val uri = container.captureRepository.capture(capture, isFrontCamera)
                val frame = lastFrame
                val result = if (frame != null) {
                    coach.evaluateOnce(frame, GuidanceLevel.COACH, _uiState.value.settings.sceneIntent)
                } else {
                    CompositionResult.empty()
                }
                container.reviewStore.put(ReviewEntry(uri, result))
                _uiState.update { it.withCaptureFinished().withLastPhotoUri(uri) }
                clearPostCaptureFadeAfterDelay()
                _events.tryEmit(CameraEvent.NavigateToReview)
            } catch (t: Throwable) {
                _uiState.update { it.withCaptureError("Couldn't save photo: ${t.message ?: "unknown error"}") }
            }
        }
    }

    /** Ends the score-badge/banner post-capture fade ~1s after it started, see [CameraUiState.postCaptureFadeActive]. */
    private fun clearPostCaptureFadeAfterDelay() {
        viewModelScope.launch {
            delay(POST_CAPTURE_FADE_MS)
            _uiState.update { it.withPostCaptureFadeEnded() }
        }
    }

    /**
     * Forwards to `CaptureRepository.loadThumbnail` — a thin pass-through so a gallery-shortcut button
     * driven by [CameraUiState.lastPhotoUri] doesn't need its own reference to [AppContainer]. Runs on
     * `Dispatchers.IO` internally (see that function's KDoc); returns null on any failure.
     */
    suspend fun loadThumbnail(uri: Uri, sizePx: Int): Bitmap? = container.captureRepository.loadThumbnail(uri, sizePx)

    // --- Onboarding ----------------------------------------------------------------------------------------

    fun onOnboardingDismissed() {
        viewModelScope.launch { container.settingsRepository.setOnboardingSeen(true) }
    }

    override fun onCleared() {
        pausePipeline()
    }

    private data class FrameUpdate(
        val composition: SmoothedComposition,
        val latencyMs: Long,
        val samplingIntervalMs: Long,
        val detectorTimings: Map<String, Long>,
        val debugFrame: DebugFrameData,
        val deviceRotationDegrees: Int = 0,
    )

    companion object {
        private const val TAG = "CameraViewModel"
        private const val UI_UPDATE_INTERVAL_MS = 100L
        private const val POST_CAPTURE_FADE_MS = 1_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CameraViewModel::class.java))
                return CameraViewModel(container) as T
            }
        }
    }
}
