package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.FrameAnalysis
import com.compositioncoach.composition.model.GuidanceLevel
import com.compositioncoach.composition.model.SmoothedComposition

/**
 * Facade the app talks to. Wraps the [CompositionEngine] (stateless per-frame evaluation) and the
 * [CompositionSmoother] (temporal stability). One instance per camera session; call [reset] on camera switch.
 *
 * Thread-safety: [process] is expected to be called from a single analysis thread.
 */
class CompositionCoach(
    private val engine: CompositionEngine,
    private val smoother: CompositionSmoother,
) {
    /** Evaluate a frame and fold it into the smoothed state the UI displays. */
    fun process(frame: FrameAnalysis, level: GuidanceLevel = GuidanceLevel.BALANCED): SmoothedComposition {
        val raw = engine.evaluate(frame, level)
        return smoother.update(raw)
    }

    /** One-off evaluation without touching smoothing state (used for the post-capture review). */
    fun evaluateOnce(frame: FrameAnalysis, level: GuidanceLevel = GuidanceLevel.COACH): CompositionResult =
        engine.evaluate(frame, level)

    fun reset() = smoother.reset()

    companion object {
        /** Default production wiring. */
        fun create(): CompositionCoach = CompositionCoach(CompositionEngine.default(), CompositionSmoother())
    }
}
