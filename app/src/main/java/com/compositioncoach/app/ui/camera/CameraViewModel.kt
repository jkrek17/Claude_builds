package com.compositioncoach.app.ui.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.compositioncoach.app.camera.CameraBindResult
import com.compositioncoach.app.camera.FlashMode
import com.compositioncoach.app.camera.LensFacing
import com.compositioncoach.app.di.AppContainer
import com.compositioncoach.app.di.ReviewEntry
import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SceneIntent
import com.compositioncoach.composition.model.SmoothedComposition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
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

    /** Bound to CameraX's `ImageAnalysis` by the screen; null when the vision pipeline is unavailable. */
    val frameAnalyzer: ImageAnalysis.Analyzer? get() = frameSource?.imageAnalyzer

    private val _uiState = MutableStateFlow(CameraUiState(analysisUnavailable = frameSource == null))
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<CameraEvent> = _events

    @Volatile private var lastFrame: FrameAnalysis? = null

    private var frameTimestampsWindowStartNanos = 0L
    private var frameCountInWindow = 0
    @Volatile private var currentFps = 0f

    /** Null until the first settings emission; used to reset smoothing only on an actual mode change, not on startup. */
    private var lastSceneIntent: SceneIntent? = null

    init {
        viewModelScope.launch {
            container.settingsRepository.settings.collect { settings ->
                _uiState.update { it.withSettings(settings) }
                frameSource?.setPoseDetectionEnabled(settings.poseDetectionEnabled)
                frameSource?.setTargetIntervalMs(settings.analysisIntervalMs)
                // Shooting mode changed: reset the smoother so advice/scoring from the old mode doesn't linger.
                if (lastSceneIntent != null && lastSceneIntent != settings.sceneIntent) {
                    coach.reset()
                }
                lastSceneIntent = settings.sceneIntent
            }
        }
        observeFrames()
    }

    private fun observeFrames() {
        val source = frameSource ?: return
        source.frames
            .onEach { frame -> lastFrame = frame; trackFps(frame.timestampNanos) }
            .map { frame ->
                val settings = _uiState.value.settings
                val composition = if (settings.guidanceEnabled) {
                    coach.process(frame, settings.guidanceLevel, settings.sceneIntent)
                } else {
                    SmoothedComposition.EMPTY
                }
                FrameUpdate(composition, frame.analysisLatencyMs, settings.analysisIntervalMs, frame.detectorTimings)
            }
            .flowOn(Dispatchers.Default)
            .sample(UI_UPDATE_INTERVAL_MS)
            .onEach(::applyFrameUpdate)
            .launchIn(viewModelScope)
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
            )
        }
        if (_uiState.value.justEnteredShootReady(previous)) {
            _events.tryEmit(CameraEvent.ShootReadyHapticTick)
        }
    }

    private fun trackFps(timestampNanos: Long) {
        if (frameTimestampsWindowStartNanos == 0L) frameTimestampsWindowStartNanos = timestampNanos
        frameCountInWindow++
        val elapsedNanos = timestampNanos - frameTimestampsWindowStartNanos
        if (elapsedNanos >= FPS_WINDOW_NANOS) {
            currentFps = frameCountInWindow * FPS_WINDOW_NANOS.toFloat() / elapsedNanos
            frameCountInWindow = 0
            frameTimestampsWindowStartNanos = timestampNanos
        }
    }

    // --- Camera lifecycle & controls -------------------------------------------------------------------

    /** Call when the camera screen enters composition. */
    fun onScreenStarted() {
        frameSource?.start()
    }

    /** Call when the camera screen leaves composition. */
    fun onScreenStopped() {
        frameSource?.stop()
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
                _uiState.update { it.withCameraBound(result.lensFacing, result.hasFlashUnit) }
            }
            is CameraBindResult.Failure -> {
                _uiState.update { it.withCameraError(result.throwable.message ?: "Camera unavailable") }
            }
        }
    }

    fun onFlashModeChanged(mode: FlashMode) {
        _uiState.update { it.withFlashMode(mode) }
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
                _uiState.update { it.withCaptureFinished() }
                _events.tryEmit(CameraEvent.NavigateToReview)
            } catch (t: Throwable) {
                _uiState.update { it.withCaptureError("Couldn't save photo: ${t.message ?: "unknown error"}") }
            }
        }
    }

    override fun onCleared() {
        frameSource?.stop()
    }

    private data class FrameUpdate(
        val composition: SmoothedComposition,
        val latencyMs: Long,
        val samplingIntervalMs: Long,
        val detectorTimings: Map<String, Long>,
    )

    companion object {
        private const val UI_UPDATE_INTERVAL_MS = 100L
        private const val FPS_WINDOW_NANOS = 1_000_000_000L

        fun factory(container: AppContainer): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(CameraViewModel::class.java))
                return CameraViewModel(container) as T
            }
        }
    }
}
