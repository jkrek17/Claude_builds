package com.compositioncoach.vision

import androidx.camera.core.ImageAnalysis
import com.compositioncoach.composition.model.FrameAnalysis
import kotlinx.coroutines.flow.Flow

/**
 * Contract between the camera/UI layer and the vision pipeline.
 *
 * The app binds [imageAnalyzer] to a CameraX [ImageAnalysis] use case and collects [frames].
 * The pipeline is responsible for throttling (target 5-10 evaluations/s, adaptive), running detectors on a
 * background executor, and normalizing all geometry to the upright, front-camera-mirrored frame
 * (see `NormalizedPoint` docs in :composition).
 */
interface FrameAnalysisSource {
    /** Latest analysed frames. Conflated: slow collectors only see the newest. */
    val frames: Flow<FrameAnalysis>

    /** Bind this to `ImageAnalysis.setAnalyzer(executor, analyzer)`. */
    val imageAnalyzer: ImageAnalysis.Analyzer

    /** Tell the pipeline whether the bound camera is front-facing (affects mirroring). */
    fun setFrontCamera(isFront: Boolean)

    /** Desired minimum interval between analyses; the pipeline may back off further under load. */
    fun setTargetIntervalMs(intervalMs: Long)

    /** Enable/disable the pose detector (more expensive; can be turned off by the settings/perf policy). */
    fun setPoseDetectionEnabled(enabled: Boolean)

    /** Start sensors (orientation). Call from the camera screen's lifecycle. */
    fun start()

    /** Stop sensors and release detectors. */
    fun stop()
}
