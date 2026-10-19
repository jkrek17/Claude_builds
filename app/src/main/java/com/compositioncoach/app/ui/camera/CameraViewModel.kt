package com.compositioncoach.app.ui.camera

import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ZoomState
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compositioncoach.app.camera.AutoLevelGeometry
import com.compositioncoach.app.camera.CameraBindResult
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.CapturePipeline
import com.compositioncoach.app.camera.FlashMode
import com.compositioncoach.app.camera.FrameFpsTracker
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.Steadiness
import com.compositioncoach.app.camera.toIntRange
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.DeviceOrientation
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.vision.VisionFeatureToggles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withContext

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
    private val motionSensor = container.motionSensor
    private val capturePipeline = container.capturePipeline

    /**
     * The controller that produced the latest successful bind ([CameraBindResult.Success.controller]). Kept so
     * zoom presets, exposure, enhancement rebinds and the capture pipeline act on the session the screen
     * bound. It only holds the application context once the screen unbinds it, so keeping it is leak-free.
     */
    private var boundController: CameraController? = null
    private var zoomLiveData: LiveData<ZoomState>? = null
    private val zoomObserver = Observer<ZoomState> { zoom ->
        _uiState.update { it.withZoomState(zoom.zoomRatio, zoom.minZoomRatio, zoom.maxZoomRatio) }
    }
    private var zoomAnimationJob: Job? = null
    private var rebindJob: Job? = null
    private var rebindAttemptedFor: CaptureEnhancement? = null
    private var settingsLoaded = false

    /** `SystemClock.elapsedRealtime()` when [lastFrame] arrived; auto-level ignores an orientation older than 1 s. */
    @Volatile private var lastFrameAtElapsedMs = 0L

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
                settingsLoaded = true
                // capture_enhancement changed (from the chips or Settings): the session type depends on it.
                rebindForEnhancementIfNeeded()
            }
        }
        viewModelScope.launch {
            motionSensor.steadiness.collect { steadiness -> _uiState.update { it.withSteadiness(steadiness) } }
        }
        // Rotation normally rides on FrameAnalysis; when no frames arrive (an extension that cannot carry
        // ImageAnalysis, or no vision pipeline), the gravity fallback keeps chrome and capture EXIF following the
        // physical hold.
        viewModelScope.launch {
            motionSensor.deviceRotationDegrees.collect { rotation ->
                if (!framesFlowing()) _uiState.update { it.withDeviceRotation(rotation) }
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
            .onEach { frame ->
                lastFrame = frame
                lastFrameAtElapsedMs = SystemClock.elapsedRealtime()
                currentFps = fpsTracker.onFrame(frame.timestampNanos)
            }
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
                    analysisRotationDegrees = frame.analysisRotationDegrees,
                    rollDegrees = frame.orientation?.rollDegrees ?: 0f,
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
                analysisRotationDegrees = update.analysisRotationDegrees,
                rollDegrees = update.rollDegrees,
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
        motionSensor.start()
        coach.reset()
    }

    /** Stops [frameSource], [thermalPolicy] and the gyro unless already inactive. Mirrors [resumePipeline]. */
    private fun pausePipeline() {
        if (!pipelineActive) return
        pipelineActive = false
        frameSource?.stop()
        thermalPolicy.stop()
        motionSensor.stop()
    }

    fun onSwitchLensRequested() {
        val next = if (_uiState.value.lensFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
        // Tell the pipeline about the mirror change *before* the rebind so no frame from the new lens is
        // normalized with the old mirroring; onCameraBindResult corrects it if the device fell back.
        frameSource?.setFrontCamera(next == LensFacing.FRONT)
        coach.reset()
        _uiState.update { it.copy(lensFacing = next) }
    }

    /**
     * Called by the screen once a (re)bind attempt finishes (and by this view model after an
     * enhancement-driven [CameraController.rebind]); wires the result into pipeline state, resets smoothing,
     * starts following the new camera's zoom state, and publishes the session's enhancements and presets.
     */
    fun onCameraBindResult(result: CameraBindResult) {
        when (result) {
            is CameraBindResult.Success -> {
                frameSource?.setFrontCamera(result.lensFacing == LensFacing.FRONT)
                coach.reset()
                result.controller?.let { boundController = it }
                observeZoom(result.camera)
                _uiState.update {
                    it.withCameraBound(result.lensFacing, result.hasFlashUnit, result.exposureRange.toIntRange(), result.session)
                }
                if (!framesFlowing()) _uiState.update { it.withDeviceRotation(motionSensor.deviceRotationDegrees.value) }
                // The preference may have changed while this bind was in flight.
                rebindForEnhancementIfNeeded()
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
     * Exposure compensation from a slider: applies the index to the bound camera (clamped by
     * `CameraController.setExposureCompensation`) and records it for the slider's position. The UI needs to
     * call only this. A rebind resets both the hardware and [CameraUiState.exposureIndex] to 0.
     */
    fun onExposureCompensationChanged(index: Int) {
        _uiState.update { it.withExposureIndex(index) }
        boundController?.setExposureCompensation(_uiState.value.exposureIndex)
    }

    fun onCaptureError(message: String) {
        _uiState.update { it.withCaptureError(message) }
    }

    fun dismissCameraError() {
        _uiState.update { it.copy(cameraError = null) }
    }

    // --- Capture -----------------------------------------------------------------------------------------

    /**
     * The shutter. [imageCapture] is the screen's `cameraController.imageCapture`; when this view model has a
     * bound controller (always, after the first successful bind) its own `imageCapture` is used instead, which
     * is the same object. Everything runs off the main thread except CameraX's `takePicture` calls; see
     * [CapturePipeline] for the steps and the README's "Capture pipeline" section for the settings that
     * affect each. Order of events the UI sees: `isCapturing` true -> (`isWaitingForSteady` true while the gyro
     * gate waits, at most 450 ms) -> [CameraEvent.NavigateToReview] with the [ReviewEntry] already in
     * `AppContainer.reviewStore` -> later, if auto-level ran, that entry is replaced with a higher `revision`.
     */
    fun onShutterClick(imageCapture: ImageCapture?) {
        val controller = boundController
        val capture = controller?.imageCapture ?: imageCapture ?: return
        if (_uiState.value.isCapturing) return
        val shutterAt = SystemClock.elapsedRealtime()
        // Snapshot the shutter-time frame and orientation now: the coaching result and the auto-level roll must
        // describe the moment the photographer pressed, not whatever arrives during the capture.
        val frameAtShutter = lastFrame
        val orientationAtShutter = frameAtShutter?.orientation
            ?.takeIf { shutterAt - lastFrameAtElapsedMs <= ORIENTATION_MAX_AGE_MS }
            ?: motionSensor.orientation
        _uiState.update { it.withCaptureStarted() }
        viewModelScope.launch {
            try {
                val gateNeeded = motionSensor.steadiness.value == Steadiness.MOVING
                if (gateNeeded) _uiState.update { it.withWaitingForSteady(true) }
                val (steadiness, waitedMs) = capturePipeline.awaitSteady()
                if (gateNeeded) _uiState.update { it.withWaitingForSteady(false) }

                val state = _uiState.value
                val session = controller?.session
                val shot = capturePipeline.capture(
                    CapturePipeline.ShotRequest(
                        controller = controller,
                        imageCapture = capture,
                        lensFacing = session?.lensFacing ?: state.lensFacing,
                        session = session,
                        burst = state.burstSharpestActive && session?.activeEnhancement == CaptureEnhancement.OFF,
                        zoomRatio = controller?.currentZoomRatio ?: state.zoomRatio,
                        exposureIndex = state.exposureIndex,
                        shutterAtElapsedMs = shutterAt,
                        steadiness = steadiness,
                        waitedForSteadyMs = waitedMs,
                    ),
                )
                val result = withContext(Dispatchers.Default) {
                    if (frameAtShutter != null) {
                        coach.evaluateOnce(frameAtShutter, GuidanceLevel.COACH, state.settings.sceneIntent)
                    } else {
                        CompositionResult.empty()
                    }
                }
                val levelling = state.settings.autoLevel && orientationAtShutter != null &&
                    AutoLevelGeometry.shouldLevel(orientationAtShutter.rollDegrees, orientationAtShutter.isReliable)
                container.reviewStore.put(ReviewEntry(shot.uri, result, shot.diagnostics, processing = levelling))
                _uiState.update { it.withCaptureFinished().withLastPhotoUri(shot.uri) }
                clearPostCaptureFadeAfterDelay()
                _events.tryEmit(CameraEvent.NavigateToReview)
                if (levelling) {
                    autoLevelInBackground(shot.uri, shot.diagnostics, orientationAtShutter!!)
                } else {
                    logDiagnostics(shot.diagnostics)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.e(TAG, "Capture failed", t)
                _uiState.update { it.withCaptureError("Couldn't save photo: ${t.message ?: "unknown error"}") }
            } finally {
                // An enhancement change that arrived mid-capture was deferred (a rebind would kill the capture).
                rebindForEnhancementIfNeeded()
            }
        }
    }

    /**
     * Auto-level runs after the review screen is already showing the photo (decode + rotate + re-encode of a
     * 12 MP JPEG is ~0.5-1 s), then updates the review entry with the applied angle and a new `revision` so
     * the review image reloads. A failure just leaves the unlevelled photo in place.
     */
    private fun autoLevelInBackground(uri: Uri, diagnostics: CaptureDiagnostics, orientation: DeviceOrientation) {
        viewModelScope.launch {
            val applied = try {
                container.autoLevelProcessor.apply(uri, orientation.rollDegrees, orientation.isReliable)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w(TAG, "Auto-level failed; keeping the photo as captured", t)
                null
            }
            val updated = diagnostics.copy(autoLevelAppliedDegrees = applied)
            container.reviewStore.updateIfCurrent(uri) {
                it.copy(diagnostics = updated, revision = if (applied != null) it.revision + 1 else it.revision, processing = false)
            }
            if (applied != null) _uiState.update { it.withLastPhotoUri(uri) }
            logDiagnostics(updated)
        }
    }

    private fun logDiagnostics(diagnostics: CaptureDiagnostics) {
        Log.i(DIAGNOSTICS_TAG, diagnostics.toLogLine())
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

    // --- Shooting mode -------------------------------------------------------------------------------------

    /**
     * The mode strip above the shutter writes the *same* DataStore value Settings > Shooting mode does, so
     * the two controls can never disagree: this is a pass-through to `SettingsRepository.setSceneIntent`,
     * and the change comes back to the UI through the settings flow this view model already collects
     * (which is also what resets the smoother on a mode change — see `init`).
     */
    fun onSceneIntentSelected(intent: SceneIntent) {
        if (_uiState.value.settings.sceneIntent == intent) return
        viewModelScope.launch { container.settingsRepository.setSceneIntent(intent) }
    }

    // --- Onboarding ----------------------------------------------------------------------------------------

    fun onOnboardingDismissed() {
        viewModelScope.launch { container.settingsRepository.setOnboardingSeen(true) }
    }

    // --- Image quality ----------------------------------------------------------------------------------

    /**
     * User picked an enhancement chip (Off/Auto/HDR/Night). Ignored unless the bound lens offers it
     * ([CameraUiState.availableEnhancements], which always contains OFF once bound). The choice is persisted
     * (`capture_enhancement`) and shown immediately; the settings emission then rebinds the camera under the
     * matching extensions session (see [rebindForEnhancementIfNeeded]). The UI must not add the enhancement
     * to its own rebind keys: this view model owns that rebind.
     */
    fun onEnhancementSelected(enhancement: CaptureEnhancement) {
        val state = _uiState.value
        if (enhancement != CaptureEnhancement.OFF && enhancement !in state.availableEnhancements) return
        _uiState.update { it.withEnhancementRequested(enhancement) }
        viewModelScope.launch { container.settingsRepository.setCaptureEnhancement(enhancement) }
    }

    /**
     * User tapped a zoom preset chip. CameraX applies `setZoomRatio` instantly, which on a logical
     * multi-camera can read as a jarring cut (especially across the 1x lens switch), so this eases to the target
     * over ~200 ms in small steps, Pixel-style. [CameraUiState.zoomRatio] follows the camera's own zoom state.
     */
    fun onZoomPresetSelected(ratio: Float) {
        val controller = boundController ?: return
        if (!(ratio > 0f)) return
        val from = (controller.currentZoomRatio ?: _uiState.value.zoomRatio).takeIf { it > 0f } ?: 1f
        zoomAnimationJob?.cancel()
        zoomAnimationJob = viewModelScope.launch {
            for (step in 1..ZOOM_ANIMATION_STEPS) {
                val t = step.toFloat() / ZOOM_ANIMATION_STEPS
                val eased = 1f - (1f - t) * (1f - t) * (1f - t) // ease-out cubic
                // Interpolate in log space: equal steps in log(zoom) look like equal steps in magnification.
                val next = kotlin.math.exp(kotlin.math.ln(from) + (kotlin.math.ln(ratio) - kotlin.math.ln(from)) * eased)
                controller.setZoomRatio(next)
                if (step < ZOOM_ANIMATION_STEPS) delay(ZOOM_ANIMATION_MS / ZOOM_ANIMATION_STEPS)
            }
        }
    }

    /**
     * Rebinds when the persisted `capture_enhancement` differs from what the current bind was built with.
     * Deferred while a capture is in progress (a rebind would abort it); the shutter calls this again when the
     * capture finishes, and every bind result calls it too, so a change can never be lost.
     */
    private fun rebindForEnhancementIfNeeded() {
        if (!settingsLoaded) return
        val controller = boundController ?: return
        val session = controller.session ?: return
        val wanted = _uiState.value.settings.captureEnhancement
        if (session.requestedEnhancement == wanted) {
            rebindAttemptedFor = null
            return
        }
        if (_uiState.value.isCapturing || rebindJob?.isActive == true) return
        // One rebind per distinct target: if the bind still reports a different request afterwards (the
        // preference could not be read), do not loop.
        if (rebindAttemptedFor == wanted) return
        rebindAttemptedFor = wanted
        rebindJob = viewModelScope.launch {
            val result = controller.rebind() ?: return@launch
            onCameraBindResult(result)
        }
    }

    /** Whether live analysis frames are arriving (and so carry the device rotation themselves). */
    private fun framesFlowing(): Boolean =
        frameSource != null && !_uiState.value.analysisPausedByEnhancement &&
            SystemClock.elapsedRealtime() - lastFrameAtElapsedMs <= ORIENTATION_MAX_AGE_MS

    private fun observeZoom(camera: Camera) {
        zoomLiveData?.removeObserver(zoomObserver)
        zoomLiveData = camera.cameraInfo.zoomState.also { it.observeForever(zoomObserver) }
    }

    override fun onCleared() {
        pausePipeline()
        zoomLiveData?.removeObserver(zoomObserver)
        zoomLiveData = null
        boundController = null
    }

    private data class FrameUpdate(
        val composition: SmoothedComposition,
        val latencyMs: Long,
        val samplingIntervalMs: Long,
        val detectorTimings: Map<String, Long>,
        val debugFrame: DebugFrameData,
        val deviceRotationDegrees: Int = 0,
        val analysisRotationDegrees: Int = 0,
        val rollDegrees: Float = 0f,
    )

    companion object {
        private const val TAG = "CameraViewModel"
        private const val UI_UPDATE_INTERVAL_MS = 100L
        private const val POST_CAPTURE_FADE_MS = 1_000L
        private const val DIAGNOSTICS_TAG = "CaptureDiagnostics"
        /** An orientation reading older than this at shutter time is not trusted for auto-level. */
        private const val ORIENTATION_MAX_AGE_MS = 1_000L
        private const val ZOOM_ANIMATION_MS = 200L
        private const val ZOOM_ANIMATION_STEPS = 8

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CameraViewModel::class.java))
                return CameraViewModel(container) as T
            }
        }
    }
}
