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
import com.compositioncoach.app.camera.CameraBindResult
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.FlashMode
import com.compositioncoach.app.camera.FrameFpsTracker
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.toIntRange
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.di.YourEyeSnapshot
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.outcome.OutcomePlanner
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.settings.TimerSecondsCodec
import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.engine.Feedback
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.vision.VisionFeatureToggles
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
 *
 * The shutter (timer, capture) lives in [CaptureFlowCoordinator]; the after-action card's background work and
 * the review's learning signals in [ReviewFlowCoordinator]; this class keeps the camera, the live state, the
 * outcome and the taste model.
 */
@OptIn(FlowPreview::class)
class CameraViewModel(private val container: AppContainer) : ViewModel() {

    private val frameSource = container.frameSourceOrNull()
    private val coach = container.coach
    private val thermalPolicy = container.thermalPolicy
    private val motionSensor = container.motionSensor
    private val capturePort = container.captureOrchestrationPort

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

    /** "Your eye" screen data (tendencies, keepers, follow rates, best three), recomputed as the journal grows. */
    val yourEye: Flow<YourEyeSnapshot> get() = container.yourEyeRepository.snapshot

    /** Auto-level after the review shows, and the lifetime of its Compare original. */
    private val levelling = PostCaptureLevelling(
        container = container,
        scope = viewModelScope,
        onPhotoRewritten = { uri -> _uiState.update { it.withLastPhotoUri(uri) } },
        onFinished = ::logDiagnostics,
    )

    private val feedbackTracker = FeedbackTracker()

    private val reviewFlow = ReviewFlowCoordinator(
        scope = viewModelScope,
        reviewStore = container.reviewStore,
        journal = container.photoJournal,
        stillAnalysis = container.stillAnalysisPort,
        coach = coach,
        cropSuggester = container.cropSuggester,
        applyFeedback = container.settingsRepository::applyFeedback,
    )

    private val captureFlow = CaptureFlowCoordinator(
        scope = viewModelScope,
        state = _uiState,
        coach = coach,
        motionSensor = motionSensor,
        capturePipeline = container.capturePipeline,
        port = capturePort,
        reviewFlow = reviewFlow,
        levelling = levelling,
        feedback = feedbackTracker,
        emit = { _events.tryEmit(it) },
        logDiagnostics = ::logDiagnostics,
        onCaptureSettled = ::rebindForEnhancementIfNeeded,
    )

    @Volatile private var lastFrame: FrameAnalysis? = null

    private val fpsTracker = FrameFpsTracker()
    @Volatile private var currentFps = 0f

    /** Null until the first settings emission; used to reset smoothing only on an actual mode change, not on startup. */
    private var lastSceneIntent: SceneIntent? = null

    /** Whether [resumePipeline]/[pausePipeline] currently consider the pipeline running; makes both idempotent. */
    @Volatile private var pipelineActive = false

    // --- Outcome and taste-model state -------------------------------------------------------------------

    /** The outcome whose side effects (intent, enhancement, lens, group mode) are in force; null before settings load. */
    private var appliedOutcome: Outcome? = null

    /** An outcome the user just picked that DataStore has not echoed back yet; stale emissions must not undo it. */
    private var pendingOutcome: Outcome? = null

    /** Apply the outcome's preferred enhancement once the (re)bound lens reports what it offers. */
    private var enhancementPlanPending = false

    /** The persisted `adaptive_weights` string and the weights the engine uses (that plus the outcome's focus). */
    private var userWeightsRaw: String? = null
    @Volatile private var effectiveWeights: AdaptiveWeights = AdaptiveWeights.NEUTRAL

    private var ghostJob: Job? = null

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
            container.settingsRepository.settings.collect(::onSettings)
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
        // A Motion/Night hold's progress, from the capture port.
        viewModelScope.launch {
            capturePort.holdProgress.collect { progress -> _uiState.update { it.withHoldProgress(progress) } }
        }
        // Restore the last saved photo's Uri on a fresh process start (CameraUiState.lastPhotoUri is
        // otherwise only set by a capture happening this process) so a gallery-shortcut button has
        // something to show immediately rather than waiting for the user's next capture.
        viewModelScope.launch {
            container.captureRepository.latestPhotoUri()?.let { uri -> _uiState.update { it.withLastPhotoUri(uri) } }
        }
        observeFrames()
    }

    private fun onSettings(settings: CoachSettings) {
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
        if (settings.adaptiveWeights != userWeightsRaw) {
            userWeightsRaw = settings.adaptiveWeights
            recomputeWeights()
        }
        // The outcome (from the strip or Settings). While a just-picked outcome is being saved, an older emission
        // must not switch it back.
        val pending = pendingOutcome
        if (pending == null || pending == settings.outcome) {
            pendingOutcome = null
            applyOutcome(settings.outcome)
        }
        settingsLoaded = true
        // capture_enhancement changed (from the chips or Settings): the session type depends on it.
        rebindForEnhancementIfNeeded()
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
                        coach.process(frame, settings.guidanceLevel, settings.sceneIntent, effectiveWeights)
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
        feedbackTracker.onComposition(SystemClock.elapsedRealtime(), update.composition)?.let(::applyFeedback)
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
        resetCoaching()
    }

    /** Stops [frameSource], [thermalPolicy] and the gyro unless already inactive. Mirrors [resumePipeline]. */
    private fun pausePipeline() {
        if (!pipelineActive) return
        pipelineActive = false
        frameSource?.stop()
        thermalPolicy.stop()
        motionSensor.stop()
        // A self-timer must not fire into a backgrounded camera.
        captureFlow.cancelCountdown()
    }

    /** Smoother and feedback tracking start over (lens switch, outcome change, resume). */
    private fun resetCoaching() {
        coach.reset()
        feedbackTracker.reset()
    }

    fun onSwitchLensRequested() {
        val next = if (_uiState.value.lensFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK
        switchLens(next)
    }

    private fun switchLens(next: LensFacing) {
        if (_uiState.value.lensFacing == next) return
        // Tell the pipeline about the mirror change *before* the rebind so no frame from the new lens is
        // normalized with the old mirroring; onCameraBindResult corrects it if the device fell back.
        frameSource?.setFrontCamera(next == LensFacing.FRONT)
        resetCoaching()
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
                resetCoaching()
                result.controller?.let { boundController = it }
                observeZoom(result.camera)
                _uiState.update {
                    it.withCameraBound(result.lensFacing, result.hasFlashUnit, result.exposureRange.toIntRange(), result.session)
                }
                if (!framesFlowing()) _uiState.update { it.withDeviceRotation(motionSensor.deviceRotationDegrees.value) }
                // What this lens offers decides the outcome's enhancement and whether it is degraded.
                refreshOutcomePlan()
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
     * is the same object. With a self-timer set this starts the countdown (pressing again cancels it); see
     * [CaptureFlowCoordinator] for the order of events the UI sees.
     */
    fun onShutterClick(imageCapture: ImageCapture?) {
        val controller = boundController
        val capture = controller?.imageCapture ?: imageCapture ?: return
        if (_uiState.value.isCapturing) return
        captureFlow.onShutter { captureFlow.shoot(shutterContext(boundController ?: controller, capture)) }
    }

    /**
     * Snapshots the shutter-time frame and orientation: the coaching result and the auto-level roll must describe
     * the moment the photo is taken, not whatever arrives during the capture.
     */
    private fun shutterContext(controller: CameraController?, capture: ImageCapture): ShutterContext {
        val shutterAt = SystemClock.elapsedRealtime()
        val frame = lastFrame
        val orientation = frame?.orientation
            ?.takeIf { shutterAt - lastFrameAtElapsedMs <= ORIENTATION_MAX_AGE_MS }
            ?: motionSensor.orientation
        return ShutterContext(controller, controller?.imageCapture ?: capture, shutterAt, frame, orientation, effectiveWeights)
    }

    private fun logDiagnostics(diagnostics: CaptureDiagnostics) {
        Log.i(DIAGNOSTICS_TAG, diagnostics.toLogLine())
    }

    /**
     * Forwards to `CaptureRepository.loadThumbnail` — a thin pass-through so a gallery-shortcut button
     * driven by [CameraUiState.lastPhotoUri] doesn't need its own reference to [AppContainer]. Runs on
     * `Dispatchers.IO` internally (see that function's KDoc); returns null on any failure.
     */
    suspend fun loadThumbnail(uri: Uri, sizePx: Int): Bitmap? = container.captureRepository.loadThumbnail(uri, sizePx)

    // --- Shooting mode -------------------------------------------------------------------------------------

    /**
     * Legacy scene strip: the scene intent is derived from the outcome now, so this selects the outcome the
     * intent maps to ([Outcome.forSceneIntent]).
     */
    fun onSceneIntentSelected(intent: SceneIntent) = onOutcomeSelected(Outcome.forSceneIntent(intent))

    /**
     * User picked an outcome on the strip: persisted (`outcome`), shown at once, and applied: scene intent (the
     * smoother resets), the first of the outcome's enhancements the lens offers, the front lens for Selfie (and
     * back again when leaving it), group mode, and `outcomeDegraded`/`outcomeDegradedNote` when the outcome's
     * first choice is missing on this phone.
     */
    fun onOutcomeSelected(outcome: Outcome) {
        if (appliedOutcome == outcome && pendingOutcome == null) return
        pendingOutcome = outcome
        applyOutcome(outcome)
        viewModelScope.launch { container.settingsRepository.setOutcome(outcome) }
    }

    private fun applyOutcome(outcome: Outcome) {
        val previous = appliedOutcome
        if (previous == outcome) return
        appliedOutcome = outcome
        _uiState.update { it.withOutcome(outcome) }
        recomputeWeights()
        capturePort.setGroupModeEnabled(outcome == Outcome.GROUP)
        if (previous != null) resetCoaching()
        val lens = _uiState.value.lensFacing
        when {
            outcome.spec.preferFrontCamera && lens == LensFacing.BACK -> switchLens(LensFacing.FRONT)
            previous?.spec?.preferFrontCamera == true && !outcome.spec.preferFrontCamera && lens == LensFacing.FRONT ->
                switchLens(LensFacing.BACK)
        }
        // On first load the persisted enhancement stands (the user may have chosen one in Pro); a real change of
        // outcome picks the outcome's enhancement, after the rebind when the lens is changing.
        if (previous != null) enhancementPlanPending = true
        val lensChanging = _uiState.value.lensFacing != lens
        if (!lensChanging) refreshOutcomePlan()
    }

    /** Re-derives the outcome's degradation for the bound lens and, when pending, applies its enhancement. */
    private fun refreshOutcomePlan() {
        val state = _uiState.value
        if (state.availableEnhancements.isEmpty()) return // not bound yet: the bind result calls this again
        val plan = OutcomePlanner.plan(state.outcome, state.availableEnhancements, capturePort::isPipelineAvailable)
        _uiState.update { it.withOutcomePlan(plan) }
        if (enhancementPlanPending) {
            enhancementPlanPending = false
            if (plan.enhancement != state.settings.captureEnhancement) onEnhancementSelected(plan.enhancement)
        }
    }

    private fun recomputeWeights() {
        effectiveWeights = OutcomePlanner.effectiveWeights(AdaptiveWeights.parse(userWeightsRaw), appliedOutcome ?: Outcome.DEFAULT)
    }

    /** User set the self-timer: 0 (off), 3 or 10 s (other values snap to the nearest). Persisted as `timer_seconds`. */
    fun onTimerSelected(seconds: Int) {
        val value = TimerSecondsCodec.decode(seconds)
        _uiState.update { it.copy(timerSeconds = value) }
        viewModelScope.launch { container.settingsRepository.setTimerSeconds(value) }
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
     * Pinch-to-zoom by the multiplicative [scale]: cancels a preset ease still in flight (so the two never fight
     * over the ratio) and applies the delta to the bound camera. [CameraUiState.zoomRatio] then follows through
     * the camera's `ZoomState`; the returned ratio (null with no camera) is for the screen's instant readout.
     */
    fun onPinchZoom(scale: Float): Float? {
        zoomAnimationJob?.cancel()
        return boundController?.applyZoomDelta(scale)
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

    // --- Review and learning (the review screen calls these on the camera's view model) -------------------

    /** Review: Keep (true) or Retake (false). Feeds the taste model when a cue was showing at capture. Call before clearing the store. */
    fun onReviewDecision(kept: Boolean) = reviewFlow.onReviewDecision(kept)

    /** Review: "Not helpful" on the Try-next-time line. */
    fun onNotHelpful() = reviewFlow.onNotHelpful()

    /** Review: the user saved [crop] (the UI writes the file via `exportCrop`; this records the choice). */
    fun onCropChosen(crop: CropSuggestion) = reviewFlow.onCropChosen(crop)

    /**
     * Review "Shoot again" / gallery long-press "Use as guide": draws [rect] as the re-shoot ghost until the next
     * capture or for [GHOST_DURATION_MS], whichever is first. Null clears it (a tap on the ghost).
     */
    fun onUseAsGhost(rect: NormalizedRect?) {
        ghostJob?.cancel()
        _uiState.update { it.withGhost(rect) }
        if (rect == null) return
        ghostJob = viewModelScope.launch {
            delay(GHOST_DURATION_MS)
            _uiState.update { if (it.ghostRect == rect) it.withGhost(null) else it }
        }
    }

    /** Review "Shoot again": the ghost from the photo under review ([ghostRectFor]). */
    fun onShootAgain() {
        container.reviewStore.current.value?.let { onUseAsGhost(ghostRectFor(it)) }
    }

    /** Settings > More > "Reset coaching preferences": neutral taste model and an empty journal. */
    fun onResetCoachingPreferences() {
        viewModelScope.launch { container.resetCoachingPreferences() }
    }

    private fun applyFeedback(feedback: Feedback) {
        viewModelScope.launch { container.settingsRepository.applyFeedback(feedback) }
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
        private const val DIAGNOSTICS_TAG = "CaptureDiagnostics"
        /** An orientation reading older than this at shutter time is not trusted for auto-level. */
        private const val ORIENTATION_MAX_AGE_MS = 1_000L
        private const val ZOOM_ANIMATION_MS = 200L
        private const val ZOOM_ANIMATION_STEPS = 8

        /** How long the re-shoot ghost stays up without a capture. */
        const val GHOST_DURATION_MS = 20_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CameraViewModel::class.java))
                return CameraViewModel(container) as T
            }
        }
    }
}
