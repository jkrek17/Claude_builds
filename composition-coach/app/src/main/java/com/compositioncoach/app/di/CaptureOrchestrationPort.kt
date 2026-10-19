package com.compositioncoach.app.di

import android.content.Context
import com.compositioncoach.app.camera.CameraController
import com.compositioncoach.app.camera.CapturePipeline
import com.compositioncoach.app.camera.PipelineSupport
import com.compositioncoach.app.camera.ShutterSound
import com.compositioncoach.app.camera.ShutterSoundPolicy
import com.compositioncoach.app.camera.Steadiness
import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.settings.ShutterSoundMode
import com.compositioncoach.vision.FrameAnalysisSource
import com.compositioncoach.vision.FrameStackingSource
import com.compositioncoach.vision.VisionFeatureToggles
import kotlinx.coroutines.flow.Flow

/**
 * What the shutter flow ([com.compositioncoach.app.ui.camera.CaptureFlowCoordinator]) and the outcome planning
 * in [com.compositioncoach.app.ui.camera.CameraViewModel] need from the capture side. Bound in
 * [AppContainer.captureOrchestrationPort] to [PipelineCaptureOrchestrationPort]; tests substitute fakes.
 */
interface CaptureOrchestrationPort {
    /** 0..1 while a hold (Motion stack or Night's steady hold) is running, null otherwise. */
    val holdProgress: Flow<Float?>

    /**
     * What the bound session can do beyond a plain capture, for `OutcomeCapturePlanner.plan`: Motion stacking
     * needs live analysis frames (a [FrameStackingSource] that the bound extension does not pause), the focus
     * stack needs a lens with manual focus.
     */
    fun pipelineSupport(controller: CameraController?, analysisPaused: Boolean): PipelineSupport

    /**
     * The steady gate before the shutter. Most outcomes wait at most `CapturePipeline.STEADY_WAIT_MS` for the
     * phone to stop moving; an outcome with a hold that is not a Motion stack (Night) waits for
     * `holdSeconds` of steadiness, publishing [holdProgress] for the ring. Returns the steadiness at the end and
     * how long it waited.
     */
    suspend fun awaitSteady(outcome: Outcome): Pair<Steadiness, Long>

    /** Takes the photo for [outcome]. Throws only when even a single capture fails. */
    suspend fun capture(outcome: Outcome, request: CapturePipeline.ShotRequest): CapturePipeline.ShotResult

    /** Group outcome on/off: enables eyes-open classification in the face detector. */
    fun setGroupModeEnabled(enabled: Boolean)

    /**
     * Called once per capture, just before [capture]. The capture pipeline itself plays the click where the
     * region requires it, so this only adds the click when the user asked for it everywhere
     * ([ShutterSoundMode.ALWAYS]) and the region did not already make the pipeline play it: never twice.
     */
    fun playShutterSound(mode: ShutterSoundMode)
}

/** Pure shutter-sound rule for the port: the pipeline already clicks where the sound is mandatory. */
object ShutterSoundRule {
    /** Whether the port should add a click on top of the pipeline's mandatory one. */
    fun portShouldPlay(mode: ShutterSoundMode, mandatoryInRegion: Boolean): Boolean =
        mode == ShutterSoundMode.ALWAYS && !mandatoryInRegion
}

/**
 * The real capture side: [CapturePipeline] for the steady gate, holds and every outcome pipeline, the vision
 * pipeline ([frameSource]) for Motion stacking and group mode.
 */
class PipelineCaptureOrchestrationPort(
    private val context: Context,
    private val pipeline: CapturePipeline,
    private val frameSource: () -> FrameAnalysisSource?,
) : CaptureOrchestrationPort {

    private val extraClick by lazy { ShutterSound(context) }

    override val holdProgress: Flow<Float?> get() = pipeline.holdProgress

    override fun pipelineSupport(controller: CameraController?, analysisPaused: Boolean): PipelineSupport = PipelineSupport(
        motionStacking = frameSource() is FrameStackingSource && !analysisPaused,
        focusStacking = controller?.lensControl?.supportsFocusStack == true,
    )

    override suspend fun awaitSteady(outcome: Outcome): Pair<Steadiness, Long> =
        if (outcome.needsHold && !outcome.isMotion) {
            pipeline.awaitSteadyHold(outcome.spec.holdSeconds * 1_000L)
        } else {
            pipeline.awaitSteady()
        }

    override suspend fun capture(outcome: Outcome, request: CapturePipeline.ShotRequest): CapturePipeline.ShotResult =
        pipeline.capture(outcome, request, frameSource() as? FrameStackingSource)

    override fun setGroupModeEnabled(enabled: Boolean) {
        (frameSource() as? VisionFeatureToggles)?.setGroupModeEnabled(enabled)
    }

    override fun playShutterSound(mode: ShutterSoundMode) {
        if (mode != ShutterSoundMode.ALWAYS) return
        if (ShutterSoundRule.portShouldPlay(mode, ShutterSoundPolicy.isMandatory(context))) extraClick.play()
    }
}
