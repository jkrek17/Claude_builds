package com.compositioncoach.app.ui.camera

import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compositioncoach.app.camera.CameraBindResult
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.setExposureCompensation
import com.compositioncoach.app.camera.CaptureDiagnostics
import com.compositioncoach.app.camera.CaptureEnhancement
import com.compositioncoach.app.camera.FlashMode
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.camera.toIntRange
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.di.YourEyeSnapshot
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.app.settings.TimerSecondsCodec
import com.compositioncoach.composition.engine.Feedback
import com.compositioncoach.composition.model.CropSuggestion
import com.compositioncoach.composition.model.NormalizedRect
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.vision.VisionFeatureToggles
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the camera screen's state, and is the one object the camera, review and settings screens talk to.
 * Collaborators, each in its own file: [LiveFrameProcessor] (per-frame scoring and the ~10 Hz UI sample),
 * [OutcomeBinding] (the outcome, its capture plan, enhancement override and rebinds), [CaptureFlowCoordinator]
 * (timer and shutter), [ReviewFlowCoordinator] (the after-action card's background work and learning signals),
 * [ReviewCopies] (crop and portrait copies), [PostCaptureLevelling] (auto-level) and [ZoomEaser].
 *
 * It holds no `Context` beyond [AppContainer]'s and no `PreviewView` or `Activity`; it keeps the [CameraController]
 * of the latest successful bind, which only holds the application context once the screen unbinds it.
 */
class CameraViewModel(private val container: AppContainer) : ViewModel() {

    private val frameSource = container.frameSourceOrNull()
    private val coach = container.coach
    private val thermalPolicy = container.thermalPolicy
    private val motionSensor = container.motionSensor
    private val capturePort = container.captureOrchestrationPort

    /** The controller that produced the latest successful bind ([CameraBindResult.Success.controller]). */
    private var boundController: CameraController? = null
    private val zoom = ZoomEaser(viewModelScope) { ratio, min, max -> _uiState.update { it.withZoomState(ratio, min, max) } }

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

    private val weights = CoachingWeights()

    /** Null until the first settings emission; used to reset smoothing only on an actual mode change, not on startup. */
    private var lastSceneIntent: SceneIntent? = null

    private val feedbackTracker = FeedbackTracker()
    private val ghost = ReshootGhost(viewModelScope, _uiState)

    private val levelling = PostCaptureLevelling(
        container = container,
        scope = viewModelScope,
        onPhotoRewritten = { uri -> _uiState.update { it.withLastPhotoUri(uri) } },
        onFinished = CaptureDiagnostics::log,
    )

    private val reviewFlow = ReviewFlowCoordinator(
        scope = viewModelScope,
        reviewStore = container.reviewStore,
        journal = container.photoJournal,
        stillAnalysis = container.stillAnalysisPort,
        coach = coach,
        cropSuggester = container.cropSuggester,
        applyFeedback = container.settingsRepository::applyFeedback,
    )

    private val reviewCopies = ReviewCopies(viewModelScope, container.captureRepository, container.reviewStore, reviewFlow::onCropChosen)

    private val outcomes = OutcomeBinding(
        scope = viewModelScope,
        state = _uiState,
        port = capturePort,
        controller = { boundController },
        persistEnhancement = container.settingsRepository::setCaptureEnhancement,
        persistOutcome = container.settingsRepository::setOutcome,
        switchLens = ::switchLens,
        onOutcomeApplied = { previous, outcome ->
            weights.onOutcome(outcome)
            if (previous != null) resetCoaching()
        },
        onRebound = ::onCameraBindResult,
    )

    private val captureFlow = CaptureFlowCoordinator(
        scope = viewModelScope,
        state = _uiState,
        coach = coach,
        motionSensor = motionSensor,
        port = capturePort,
        reviewFlow = reviewFlow,
        levelling = levelling,
        feedback = feedbackTracker,
        emit = { _events.tryEmit(it) },
        logDiagnostics = CaptureDiagnostics::log,
        onCaptureSettled = outcomes::rebindIfNeeded,
    )

    private val pipeline = PipelineLifecycle(
        startables = listOfNotNull(frameSource?.let { PipelineLifecycle.Part(it::start, it::stop) }) + listOf(
            PipelineLifecycle.Part(thermalPolicy::start, thermalPolicy::stop),
            PipelineLifecycle.Part(motionSensor::start, motionSensor::stop),
        ),
        onResumed = ::resetCoaching,
        // A self-timer must not fire into a backgrounded camera.
        onPaused = captureFlow::cancelCountdown,
    )

    private val liveFrames = frameSource?.let { source ->
        LiveFrameProcessor(
            scope = viewModelScope,
            source = source,
            coach = coach,
            state = _uiState,
            feedbackTracker = feedbackTracker,
            weights = { weights.effective },
            onShootReadyEntered = { _events.tryEmit(CameraEvent.ShootReadyHapticTick) },
            onFeedback = ::applyFeedback,
        )
    }

    init {
        viewModelScope.launch { container.settingsRepository.settings.collect(::onSettings) }
        viewModelScope.launch { motionSensor.steadiness.collect { s -> _uiState.update { it.withSteadiness(s) } } }
        // Without frames (an extension pausing analysis, or no vision pipeline) the gravity fallback carries rotation.
        viewModelScope.launch {
            motionSensor.deviceRotationDegrees.collect { r -> if (!framesFlowing()) _uiState.update { it.withDeviceRotation(r) } }
        }
        // Device-health cadence tier (ThermalPolicy): forwarded raw; VisionPipeline combines it with the settings.
        viewModelScope.launch {
            thermalPolicy.tier.collect { tier ->
                _uiState.update { it.withPerformanceTier(tier) }
                (frameSource as? VisionFeatureToggles)?.setPerformanceTier(tier)
            }
        }
        // A Motion stack's or Night hold's progress (the shutter ring), and the last photo for the thumbnail.
        viewModelScope.launch { capturePort.holdProgress.collect { p -> _uiState.update { it.withHoldProgress(p) } } }
        viewModelScope.launch { container.captureRepository.latestPhotoUri()?.let { u -> _uiState.update { it.withLastPhotoUri(u) } } }
        liveFrames?.start()
    }

    private fun onSettings(settings: CoachSettings) {
        _uiState.update { it.withSettings(settings) }
        frameSource?.applySettings(settings)
        // Shooting mode changed: reset the smoother so advice/scoring from the old mode doesn't linger.
        if (lastSceneIntent != null && lastSceneIntent != settings.sceneIntent) coach.reset()
        lastSceneIntent = settings.sceneIntent
        weights.onUserWeights(settings.adaptiveWeights)
        // The outcome (strip or Settings) and a changed capture_enhancement, which may need a rebind.
        outcomes.onSettings(settings.outcome)
    }

    // --- Camera lifecycle & controls -------------------------------------------------------------------

    /** Camera screen entered / left composition, Activity resumed / paused: all idempotent, see [PipelineLifecycle]. */
    fun onScreenStarted() = pipeline.resume()
    fun onScreenStopped() = pipeline.pause()
    fun onScreenResumed() = pipeline.resume()
    fun onScreenPaused() = pipeline.pause()

    /** Smoother and feedback tracking start over (lens switch, outcome change, resume). */
    private fun resetCoaching() {
        coach.reset()
        feedbackTracker.reset()
    }

    fun onSwitchLensRequested() {
        switchLens(if (_uiState.value.lensFacing == LensFacing.BACK) LensFacing.FRONT else LensFacing.BACK)
    }

    private fun switchLens(next: LensFacing) {
        if (_uiState.value.lensFacing == next) return
        // Mirror change before the rebind, so no new-lens frame is normalized with the old mirroring.
        frameSource?.setFrontCamera(next == LensFacing.FRONT)
        resetCoaching()
        _uiState.update { it.copy(lensFacing = next) }
    }

    /** Called on the screen's controller right before it binds, so the bind already carries the outcome's setup. */
    fun configureController(controller: CameraController) = outcomes.configure(controller)

    /** A (re)bind finished (the screen's, or a rebind asked for here): publish it and re-plan the outcome for the lens. */
    fun onCameraBindResult(result: CameraBindResult) {
        when (result) {
            is CameraBindResult.Success -> {
                frameSource?.setFrontCamera(result.lensFacing == LensFacing.FRONT)
                resetCoaching()
                result.controller?.let { boundController = it }
                zoom.follow(result.camera)
                _uiState.update {
                    it.withCameraBound(result.lensFacing, result.hasFlashUnit, result.exposureRange.toIntRange(), result.session)
                }
                if (!framesFlowing()) _uiState.update { it.withDeviceRotation(motionSensor.deviceRotationDegrees.value) }
                outcomes.onBound()
            }
            is CameraBindResult.Failure -> _uiState.update { it.withCameraError(result.throwable.message.orEmpty()) }
        }
    }

    fun onFlashModeChanged(mode: FlashMode) = _uiState.update { it.withFlashMode(mode) }

    /** Exposure compensation from the slider; a rebind resets both the hardware and the index to 0. */
    fun onExposureCompensationChanged(index: Int) {
        _uiState.update { it.withExposureIndex(index) }
        boundController?.setExposureCompensation(_uiState.value.exposureIndex)
    }

    fun dismissCameraError() = _uiState.update { it.copy(cameraError = null) }

    // --- Capture -----------------------------------------------------------------------------------------

    /**
     * The shutter. [imageCapture] is the screen's `cameraController.imageCapture`; the bound controller's own is
     * preferred (the same object). With a self-timer set this starts the countdown (pressing again cancels it).
     */
    fun onShutterClick(imageCapture: ImageCapture?) {
        val controller = boundController
        val capture = controller?.imageCapture ?: imageCapture ?: return
        if (_uiState.value.isCapturing) return
        captureFlow.onShutter { captureFlow.shoot(shutterContext(boundController ?: controller, capture)) }
    }

    /** The shutter-time frame and orientation: the review and auto-level describe the moment the photo was taken. */
    private fun shutterContext(controller: CameraController?, capture: ImageCapture): ShutterContext {
        val shutterAt = SystemClock.elapsedRealtime()
        val frame = liveFrames?.lastFrame
        val frameAge = shutterAt - (liveFrames?.lastFrameAtElapsedMs ?: 0L)
        val orientation = frame?.orientation?.takeIf { frameAge <= ORIENTATION_MAX_AGE_MS } ?: motionSensor.orientation
        return ShutterContext(controller, controller?.imageCapture ?: capture, shutterAt, frame, orientation, weights.effective)
    }

    // --- Outcome, timer, enhancement, zoom ----------------------------------------------------------------

    /** The user picked an outcome on the strip; see [OutcomeBinding]. */
    fun onOutcomeSelected(outcome: Outcome) = outcomes.select(outcome)

    /** Self-timer: 0 (off), 3 or 10 s (other values snap to the nearest). Persisted as `timer_seconds`. */
    fun onTimerSelected(seconds: Int) {
        val value = TimerSecondsCodec.decode(seconds)
        _uiState.update { it.copy(timerSeconds = value) }
        viewModelScope.launch { container.settingsRepository.setTimerSeconds(value) }
    }

    fun onOnboardingDismissed() {
        viewModelScope.launch { container.settingsRepository.setOnboardingSeen(true) }
    }

    /**
     * The user picked an enhancement (pill or Settings > More). Ignored unless the bound lens offers it. Persisted
     * (`capture_enhancement`), shown at once, and it wins over the outcome's choice until the next outcome change;
     * this view model owns the rebind it needs, so the screen must not key its own bind on the enhancement.
     */
    fun onEnhancementSelected(enhancement: CaptureEnhancement) {
        if (enhancement != CaptureEnhancement.OFF && enhancement !in _uiState.value.availableEnhancements) return
        _uiState.update { it.withEnhancementRequested(enhancement) }
        viewModelScope.launch { container.settingsRepository.setCaptureEnhancement(enhancement) }
        outcomes.onUserPickedEnhancement(enhancement)
    }

    /** A zoom preset chip: eased over ~200 ms; [CameraUiState.zoomRatio] follows the camera's own zoom state. */
    fun onZoomPresetSelected(ratio: Float) {
        boundController?.let { zoom.easeTo(it, ratio, _uiState.value.zoomRatio) }
    }

    /** Pinch-to-zoom by [scale]; returns the applied ratio (null with no camera) for the screen's readout. */
    fun onPinchZoom(scale: Float): Float? = zoom.pinch(boundController, scale)

    /** Whether live analysis frames are arriving (and so carry the device rotation themselves). */
    private fun framesFlowing(): Boolean =
        frameSource != null && !_uiState.value.analysisPausedByEnhancement &&
            SystemClock.elapsedRealtime() - (liveFrames?.lastFrameAtElapsedMs ?: 0L) <= ORIENTATION_MAX_AGE_MS

    // --- Review and learning (the review screen calls these on the camera's view model) -------------------

    /** Review: Keep (true) or Retake (false). Feeds the taste model when a cue was showing at capture. Call before clearing the store. */
    fun onReviewDecision(kept: Boolean) = reviewFlow.onReviewDecision(kept)

    /** Review: "Not helpful" on the Try-next-time line. */
    fun onNotHelpful() = reviewFlow.onNotHelpful()

    /** Review "Save crop": writes the cropped copy, then records the choice ([onDone] says whether it saved). */
    fun onCropChosen(crop: CropSuggestion, onDone: (Boolean) -> Unit = {}) = reviewCopies.saveCrop(crop, onDone)

    /** Review "Portrait" > "Save as copy": exports the background-separated preview as a new photo. */
    fun onSavePortraitCopy(onDone: (Boolean) -> Unit = {}) = reviewCopies.savePortrait(onDone)

    /** Review "Shoot again" / gallery long-press "Use as guide": the re-shoot ghost ([ReshootGhost]); null clears it. */
    fun onUseAsGhost(rect: NormalizedRect?) = ghost.show(rect)

    /** Settings > More and "Your eye" > Start fresh: neutral taste model and an empty journal. */
    fun onResetCoachingPreferences() {
        viewModelScope.launch { container.resetCoachingPreferences() }
    }

    private fun applyFeedback(feedback: Feedback) {
        viewModelScope.launch { container.settingsRepository.applyFeedback(feedback) }
    }

    override fun onCleared() {
        pipeline.pause()
        zoom.release()
        boundController = null
    }

    companion object {
        /** An orientation reading older than this at shutter time is not trusted for auto-level. */
        private const val ORIENTATION_MAX_AGE_MS = 1_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CameraViewModel::class.java))
                return CameraViewModel(container) as T
            }
        }
    }
}
