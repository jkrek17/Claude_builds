package com.compositioncoach.app.ui.camera

import com.compositioncoach.app.outcome.Outcome
import com.compositioncoach.app.outcome.OutcomePlanner
import com.compositioncoach.composition.engine.AdaptiveWeights

/**
 * The taste model the engine scores with, live and at capture: the user's persisted `adaptive_weights` with the
 * selected outcome's coaching focus weighted up ([OutcomePlanner.effectiveWeights]). Recomputed only when either
 * input changes; read from the frame thread, hence [effective] is volatile.
 */
internal class CoachingWeights {
    private var userRaw: String? = null
    private var outcome: Outcome = Outcome.DEFAULT

    @Volatile var effective: AdaptiveWeights = AdaptiveWeights.NEUTRAL
        private set

    fun onUserWeights(raw: String) {
        if (raw == userRaw) return
        userRaw = raw
        recompute()
    }

    fun onOutcome(next: Outcome) {
        outcome = next
        recompute()
    }

    private fun recompute() {
        effective = OutcomePlanner.effectiveWeights(AdaptiveWeights.parse(userRaw), outcome)
    }
}
