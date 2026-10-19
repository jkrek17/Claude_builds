package com.compositioncoach.app.ui.camera

/**
 * Starts and stops the camera screen's sensors and detectors (vision pipeline, thermal policy, gyro) with the
 * screen. Two pairs of hooks feed it: composition entry/exit (`DisposableEffect`) and the Activity's
 * resume/pause (`LifecycleEventEffect`), because backgrounding the app does not leave composition and would
 * otherwise leave the orientation sensor registered and the ML Kit detectors allocated. Both are idempotent:
 * whichever fires first does the work and the other is a no-op until the opposite event. [onResumed] resets the
 * coach smoother; [onPaused] cancels a running self-timer.
 */
internal class PipelineLifecycle(
    private val startables: List<Part>,
    private val onResumed: () -> Unit,
    private val onPaused: () -> Unit,
) {
    /** Something with a start and a stop (a sensor, the vision pipeline). */
    class Part(val start: () -> Unit, val stop: () -> Unit)

    @Volatile private var active = false

    fun resume() {
        if (active) return
        active = true
        startables.forEach { it.start() }
        onResumed()
    }

    fun pause() {
        if (!active) return
        active = false
        startables.forEach { it.stop() }
        onPaused()
    }
}
