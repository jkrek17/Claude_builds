package com.compositioncoach.app.di

import android.media.MediaActionSound
import android.util.Log
import androidx.camera.core.CameraInfo
import com.compositioncoach.app.camera.CapturePipeline
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.settings.ShutterSoundMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.compositioncoach.app.outcome.CapturePipeline as OutcomePipeline

/**
 * What the shutter flow needs from the capture side for Wave 2 outcomes. Defined here so the state layer
 * compiles before the capture engineer's classes land; [DefaultCaptureOrchestrationPort] does what today's
 * code can, and the integrator swaps in the real one in [AppContainer.captureOrchestrationPort].
 *
 * INTEGRATOR binds:
 *  * [capture] -> `CapturePipeline.capture(outcome, request)` (Motion stacks, focus stack, Night hold); it must
 *    also publish its hold progress on [holdProgress].
 *  * [holdProgress] -> the Motion/Night hold's 0..1 progress flow (null when no hold is running).
 *  * [isPipelineAvailable] -> whether `MotionCapture` / `FocusStacker` can run on the bound lens.
 *  * [setGroupModeEnabled] -> `VisionFeatureToggles.setGroupModeEnabled` (face classification for eyes-open).
 *  * [playShutterSound] -> `ShutterSoundPolicy` (keep the [ShutterSoundMode.ALWAYS] override).
 */
interface CaptureOrchestrationPort {
    /** 0..1 while a hold capture is accumulating, null otherwise. */
    val holdProgress: StateFlow<Float?>

    /** Whether [pipeline] can run on this phone; false makes the outcome "degraded" (it takes a normal photo). */
    fun isPipelineAvailable(pipeline: OutcomePipeline): Boolean

    /** Takes the photo for [outcome]. Throws only when even a single capture fails. */
    suspend fun capture(outcome: Outcome, request: CapturePipeline.ShotRequest): CapturePipeline.ShotResult

    /** Group outcome on/off: enables eyes-open classification in the face detector. */
    fun setGroupModeEnabled(enabled: Boolean)

    /** Called once per capture, just before the shutter fires. Plays the sound when [mode] or the region requires it. */
    fun playShutterSound(mode: ShutterSoundMode)
}

/** Pure shutter-sound rule: always when the user asked, otherwise only where the device reports it mandatory. */
object ShutterSoundRule {
    fun shouldPlay(mode: ShutterSoundMode, mandatoryInRegion: Boolean): Boolean =
        mode == ShutterSoundMode.ALWAYS || mandatoryInRegion
}

/**
 * Today's behaviour behind the Wave 2 port: every outcome takes the existing single / sharpest-of-burst
 * capture, there is never a hold, group mode is a no-op, and the shutter sound follows CameraX's
 * `CameraInfo.mustPlayShutterSound()` (the region/device requirement) through `MediaActionSound`.
 */
class DefaultCaptureOrchestrationPort(
    private val pipeline: CapturePipeline,
) : CaptureOrchestrationPort {

    private val progress = MutableStateFlow<Float?>(null)
    override val holdProgress: StateFlow<Float?> = progress.asStateFlow()

    private val sound: MediaActionSound? by lazy {
        runCatching { MediaActionSound().also { it.load(MediaActionSound.SHUTTER_CLICK) } }
            .onFailure { Log.w(TAG, "MediaActionSound unavailable", it) }
            .getOrNull()
    }

    override fun isPipelineAvailable(pipeline: OutcomePipeline): Boolean = when (pipeline) {
        OutcomePipeline.SINGLE, OutcomePipeline.BURST_SHARPEST -> true
        // A missing focus stack falls back to one capture, which is still a fine landscape: not worth a note.
        OutcomePipeline.FOCUS_STACK -> true
        OutcomePipeline.MOTION_AVERAGE, OutcomePipeline.MOTION_LIGHTEN, OutcomePipeline.MOTION_MEDIAN -> false
    }

    override suspend fun capture(outcome: Outcome, request: CapturePipeline.ShotRequest): CapturePipeline.ShotResult =
        pipeline.capture(request)

    override fun setGroupModeEnabled(enabled: Boolean) = Unit

    override fun playShutterSound(mode: ShutterSoundMode) {
        val mandatory = runCatching { CameraInfo.mustPlayShutterSound() }.getOrDefault(false)
        if (ShutterSoundRule.shouldPlay(mode, mandatory)) {
            runCatching { sound?.play(MediaActionSound.SHUTTER_CLICK) }
        }
    }

    private companion object {
        const val TAG = "CaptureOrchestration"
    }
}
