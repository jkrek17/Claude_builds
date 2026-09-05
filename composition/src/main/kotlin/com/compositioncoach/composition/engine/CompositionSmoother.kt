package com.compositioncoach.composition.engine

import com.compositioncoach.composition.model.CompositionResult
import com.compositioncoach.composition.model.SmoothedComposition

/**
 * Temporal smoothing + hysteresis so the score glides and advice does not flicker.
 *
 * STATUS: STUB. To be implemented by the :composition owner. Keep the public API.
 */
class CompositionSmoother {
    fun update(result: CompositionResult): SmoothedComposition = SmoothedComposition(
        displayScore = result.score,
        activeRecommendations = result.recommendations,
        isShootReady = result.isShootReady,
        scene = result.scene,
        primarySubject = result.primarySubject,
        raw = result,
    )

    fun reset() {}
}
