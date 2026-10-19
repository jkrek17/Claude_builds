package com.compositioncoach.app.ui.camera

import android.os.SystemClock
import android.util.Log
import com.compositioncoach.app.camera.FrameFpsTracker
import com.compositioncoach.composition.engine.AdaptiveWeights
import com.compositioncoach.composition.engine.CompositionCoach
import com.compositioncoach.composition.engine.Feedback
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.SmoothedComposition
import com.compositioncoach.app.settings.CoachSettings
import com.compositioncoach.vision.FrameAnalysisSource
import com.compositioncoach.vision.VisionFeatureToggles
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.plus

/**
 * The live half of [CameraViewModel]: every analysed [FrameAnalysis] is scored immediately on
 * [Dispatchers.Default] (so the temporal smoother in [coach] sees every frame and stays stable), but the *UI* only
 * redraws at ~10 Hz via [sample]: the coach can process frames faster than the screen needs to repaint. Keeps
 * the latest frame for the shutter ([lastFrame], [lastFrameAtElapsedMs]), fires [onShootReadyEntered] once per
 * entry into shoot-ready, and feeds the [feedbackTracker] (followed cues) into [onFeedback].
 */
@OptIn(FlowPreview::class)
internal class LiveFrameProcessor(
    private val scope: CoroutineScope,
    private val source: FrameAnalysisSource,
    private val coach: CompositionCoach,
    private val state: MutableStateFlow<CameraUiState>,
    private val feedbackTracker: FeedbackTracker,
    private val weights: () -> AdaptiveWeights,
    private val onShootReadyEntered: () -> Unit,
    private val onFeedback: (Feedback) -> Unit,
) {
    @Volatile var lastFrame: FrameAnalysis? = null
        private set

    /** `SystemClock.elapsedRealtime()` when [lastFrame] arrived; auto-level ignores an orientation older than 1 s. */
    @Volatile var lastFrameAtElapsedMs = 0L
        private set

    private val fpsTracker = FrameFpsTracker()
    @Volatile private var currentFps = 0f

    /**
     * Last-resort net for the frame-processing pipeline: logs and lets the scope carry on rather than letting an
     * uncaught exception here crash the app (the per-frame `try`/`catch` in [start] is the primary defense and is
     * what actually keeps *coaching* alive frame-to-frame; this only guards the coroutine machinery around it).
     */
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Frame processing pipeline failed; camera preview keeps running", throwable)
    }

    fun start() {
        source.frames
            .onEach { frame ->
                lastFrame = frame
                lastFrameAtElapsedMs = SystemClock.elapsedRealtime()
                currentFps = fpsTracker.onFrame(frame.timestampNanos)
            }
            .map { frame ->
                val settings = state.value.settings
                val composition = if (settings.guidanceEnabled) {
                    // A detector/engine exception on one frame must not stop coaching for every frame after it:
                    // catch inside the per-frame map so a bad frame is skipped (the previous composition keeps
                    // showing) and the flow keeps collecting.
                    try {
                        coach.process(frame, settings.guidanceLevel, settings.sceneIntent, weights())
                    } catch (t: Throwable) {
                        Log.e(TAG, "Composition engine threw while scoring a frame; keeping the last result", t)
                        state.value.composition
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
            .onEach(::apply)
            .launchIn(scope + exceptionHandler)
    }

    private fun apply(update: FrameUpdate) {
        val previous = state.value
        state.update {
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
        if (state.value.justEnteredShootReady(previous)) onShootReadyEntered()
        feedbackTracker.onComposition(SystemClock.elapsedRealtime(), update.composition)?.let(onFeedback)
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

    private companion object {
        const val TAG = "CameraViewModel"
        const val UI_UPDATE_INTERVAL_MS = 100L
    }
}

/**
 * Forwards the settings the vision pipeline follows: pose detection, the analysis interval (battery saver), and,
 * through the optional [VisionFeatureToggles], object detection and the subject mask (forced off by battery saver,
 * `CoachSettings.effectiveSubjectMaskEnabled`).
 */
internal fun FrameAnalysisSource.applySettings(settings: CoachSettings) {
    setPoseDetectionEnabled(settings.poseDetectionEnabled)
    setTargetIntervalMs(settings.analysisIntervalMs)
    (this as? VisionFeatureToggles)?.let { toggles ->
        toggles.setObjectDetectionEnabled(settings.detectObjectsEnabled)
        toggles.setSegmentationEnabled(settings.effectiveSubjectMaskEnabled)
    }
}
