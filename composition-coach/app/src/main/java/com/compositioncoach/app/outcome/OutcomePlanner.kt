package com.compositioncoach.app.outcome

import com.compositioncoach.composition.engine.AdaptiveWeights

/**
 * Pure state-layer decisions behind an [Outcome] (the taste-model focus and the hold chip), unit-tested in
 * `OutcomePlannerTest`. How an outcome is *captured* on a given phone is `camera/OutcomeCapturePlanner`.
 */
object OutcomePlanner {

    /** How much an outcome's [OutcomeSpec.coachingFocus] categories are weighted up (on top of the taste model). */
    const val FOCUS_BOOST = 1.15f

    /**
     * The taste model the engine sees for [outcome]: the user's [weights] with the outcome's coaching-focus
     * categories weighted up by [FOCUS_BOOST] (still clamped to the model's bounds). Returns [weights] itself
     * when the outcome has no focus. Crop history is not needed for scoring and is not carried over.
     */
    fun effectiveWeights(weights: AdaptiveWeights, outcome: Outcome): AdaptiveWeights {
        val focus = outcome.spec.coachingFocus
        if (focus.isEmpty()) return weights
        return AdaptiveWeights.of(weights.allMultipliers().mapValues { (category, m) -> if (category in focus) m * FOCUS_BOOST else m })
    }

    /** The chip while a hold is pending: the outcome's instruction, only for outcomes that need a hold. */
    fun holdInstruction(outcome: Outcome, holdPending: Boolean): String? =
        if (holdPending && outcome.needsHold) outcome.spec.instruction else null
}
